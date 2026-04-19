package com.tool.pipeline.steps;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.TableSchema;
import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.writer.SchemaWriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * Connects to SQL Server and TiDB, iterates all selected databases,
 * and for each database: extracts, converts, and writes schema.
 *
 * Reads from context:
 *   "dryRun"          (Boolean)
 *   "schemas"         (List<String>)
 *   "tables"          (List<String>)
 *   "dropIfExists"    (Boolean)
 *   "continueOnError" (Boolean)
 *
 * Writes to context:
 *   "dbSummaries"   (List<SummaryPrinter.DbSummary>)
 *   "totalFailed"   (Integer)
 */
public class SchemaMigrateStep implements MigrationStep {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final StructuredLogger log;
    private final ProgressReporter progress;

    public SchemaMigrateStep(AppConfig config, SchemaExtractor extractor,
                             SchemaConverter converter, SchemaWriter writer,
                             StructuredLogger log, ProgressReporter progress) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.log       = log;
        this.progress  = progress;
    }

    @Override
    public String name() { return "SchemaMigrate"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        boolean dryRun         = Boolean.TRUE.equals(ctx.get("dryRun", Boolean.class));
        List<String> schemas   = ctx.get("schemas", List.class);
        List<String> tables    = ctx.get("tables",  List.class);
        boolean dropIfExists   = Boolean.TRUE.equals(ctx.get("dropIfExists", Boolean.class));
        boolean continueOnError= Boolean.TRUE.equals(ctx.get("continueOnError", Boolean.class));

        List<String> dbNames;
        try (Connection masterConn = DriverManager.getConnection(
                config.getSource().sqlServerJdbcUrlNoDB(),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = extractor.listDatabases(masterConn);
        }
        log.log("INFO", "Found databases", "count", dbNames.size());
        progress.setDbNameWidth(dbNames.stream().mapToInt(String::length).max().orElse(8));

        int totalFailed = 0;
        boolean stoppedEarlyGlobal = false;
        boolean stoppedByConflict = false;
        List<SummaryPrinter.DbSummary> summaries = new ArrayList<>();

        for (String dbName : dbNames) {
            try (Connection ssConn = DriverManager.getConnection(
                     config.getSource().sqlServerJdbcUrlForDB(dbName),
                     config.getSource().getUsername(),
                     config.getSource().getPassword());
                 Connection tidbConn = dryRun ? null : openTiDB(dbName)) {

                DbResult dr = migrateOneDb(ssConn, tidbConn, dbName,
                        schemas, tables, dropIfExists, continueOnError, dryRun);
                totalFailed += dr.failed;

                summaries.add(new SummaryPrinter.DbSummary(
                        dbName,
                        dr.allTables.size(),
                        dr.succeededTables.size(),
                        dr.convResults));

                if (dr.stoppedEarly) {
                    progress.clear();
                    if (dr.conflictStop()) {
                        System.out.println("[ERROR] Stopped early: table name conflict — see any2tidb.log");
                    } else {
                        System.out.println("[WARN ] Stopped early (continueOnError=false) — see any2tidb.log");
                    }
                    stoppedEarlyGlobal = true;
                    stoppedByConflict = dr.conflictStop();
                    break;
                }
            }
        }

        ctx.put("dbSummaries", summaries);
        ctx.put("totalFailed", totalFailed);

        return stoppedEarlyGlobal
                ? StepResult.fatal(stoppedByConflict ? "stopped early: table name conflict"
                                                     : "stopped early: continueOnError=false")
                : StepResult.ok("schema migration complete, failed=" + totalFailed);
    }

    // ── Internal result carrier ───────────────────────────────────────────────

    private record DbResult(
            int failed, int warned, int skipped, boolean stoppedEarly, boolean conflictStop,
            List<String[]> allTables, List<String[]> succeededTables,
            Map<String, ConversionResult> convResults) {}

    private DbResult migrateOneDb(Connection ssConn, Connection tidbConn,
                                  String dbName, List<String> schemas, List<String> tables,
                                  boolean dropIfExists, boolean continueOnError,
                                  boolean dryRun) throws Exception {
        List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
        log.log("INFO", "Starting conversion", "db", dbName, "tables", tableList.size());

        // Pre-check: table name conflicts across schemas
        Map<String, List<String>> nameToSchemas = new LinkedHashMap<>();
        for (String[] entry : tableList) {
            nameToSchemas.computeIfAbsent(entry[1], k -> new ArrayList<>()).add(entry[0]);
        }
        List<String> conflicts = nameToSchemas.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "  \"" + e.getKey() + "\"  →  " + String.join(", ",
                        e.getValue().stream().map(s -> s + "." + e.getKey()).toList()))
                .toList();
        if (!conflicts.isEmpty()) {
            progress.clear();
            System.out.println("[ERROR] Table name conflict in [" + dbName + "] — see any2tidb.log");
            log.log("ERROR", "Table name conflict", "db", dbName);
            conflicts.forEach(line -> log.log("ERROR", "conflict", "tables", line));
            return new DbResult(1, 0, 0, true, true, tableList, List.of(), Map.of());
        }

        int total = tableList.size();
        int succeeded = 0, warned = 0, failed = 0, skipped = 0;
        List<String[]> succeededTables = new ArrayList<>();
        Map<String, ConversionResult> convResults = new LinkedHashMap<>();
        boolean stopEarly = false;

        for (int i = 0; i < total; i++) {
            String[] entry      = tableList.get(i);
            String schemaName   = entry[0];
            String tableName    = entry[1];
            String fullName     = schemaName + "." + tableName;

            progress.print(dbName, i, total, fullName);
            ConversionResult result = new ConversionResult(fullName);

            try {
                TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);
                if (ddl == null) {
                    // result already has ERROR set by converter
                } else if (dryRun) {
                    writer.printDDL(fullName, ddl);
                } else {
                    writer.executeDDL(tidbConn, ddl, result);
                }
            } catch (Exception e) {
                result.setError(e.getMessage());
            }

            switch (result.getStatus()) {
                case OK -> { succeeded++; succeededTables.add(entry); convResults.put(fullName, result); }
                case WARN -> { succeeded++; warned++; succeededTables.add(entry); convResults.put(fullName, result); }
                case ERROR -> { failed++; convResults.put(fullName, result); if (!continueOnError) stopEarly = true; }
                case SKIP  -> { skipped++; convResults.put(fullName, result); }
            }
            if (stopEarly) break;
        }

        progress.print(dbName, total, total, "");
        System.out.println();

        return new DbResult(failed, warned, skipped, stopEarly, false,
                tableList, succeededTables, convResults);
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrlNoDB(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "`");
            st.execute("USE `" + dbName + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }
}
