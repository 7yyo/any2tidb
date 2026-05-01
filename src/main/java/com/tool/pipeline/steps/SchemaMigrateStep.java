package com.tool.pipeline.steps;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.TableSchema;
import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.common.FilterUtils;
import com.tool.schema.writer.SchemaWriter;
import com.tool.source.SourceDriver;

import static com.tool.common.SqlUtils.escapeBacktick;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * Connects to source database and TiDB, iterates all selected databases,
 * and for each database: extracts, converts, and writes schema.
 *
 * Reads from context:
 *   "dryRun"          (Boolean)
 *   "databases"       (List<String>)
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
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrateStep.class);
    private final ProgressReporter progress;

    public SchemaMigrateStep(AppConfig config, SchemaExtractor extractor,
                             SchemaConverter converter, SchemaWriter writer,
                             ProgressReporter progress, SourceDriver sourceDriver) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.progress  = progress;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "SchemaMigrate"; }

    /** Collects all DDL for one database and writes it to a .sql file. Returns the file path. */
    private Path writeDryRunFile(String dbName, String ddlBlock) throws IOException {
        String filename = dbName + ".sql";
        Path out = Path.of(filename);
        try (PrintWriter pw = new PrintWriter(out.toFile(), StandardCharsets.UTF_8)) {
            pw.println("-- any2tidb dry-run  db=" + dbName);
            pw.println("-- Source to TiDB directly: mysql -h host -P 4000 -u root < " + filename);
            pw.println();
            pw.println("USE `" + escapeBacktick(dbName) + "`;");
            pw.println();
            pw.print(ddlBlock);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        boolean dryRun         = Boolean.TRUE.equals(ctx.get("dryRun", Boolean.class));
        List<String> databases = ctx.get("databases", List.class);
        List<String> tables    = ctx.get("tables",  List.class);
        boolean dropIfExists   = Boolean.TRUE.equals(ctx.get("dropIfExists", Boolean.class));
        boolean continueOnError= Boolean.TRUE.equals(ctx.get("continueOnError", Boolean.class));

        List<String> dbNames;
        try (Connection masterConn = DriverManager.getConnection(
                sourceDriver.buildJdbcUrl(config.getSource()),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = extractor.listDatabases(masterConn);
        }
        dbNames = FilterUtils.filterNames(dbNames, databases);
        Log.info(log, "Found databases", "count", dbNames.size());
        progress.setDbNameWidth(dbNames.stream().mapToInt(String::length).max().orElse(8));

        int totalFailed = 0;
        boolean stoppedEarlyGlobal = false;
        boolean stoppedByConflict = false;
        List<SummaryPrinter.DbSummary> summaries = new ArrayList<>();

        for (String dbName : dbNames) {
            try (Connection ssConn = DriverManager.getConnection(
                     sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                     config.getSource().getUsername(),
                     config.getSource().getPassword());
                 Connection tidbConn = dryRun ? null : openTiDB(dbName)) {

                DbResult dr = migrateOneDb(ssConn, tidbConn, dbName,
                        databases, tables, dropIfExists, continueOnError, dryRun);
                totalFailed += dr.failed;

                summaries.add(new SummaryPrinter.DbSummary(
                        dbName,
                        dr.allTables.size(),
                        dr.succeededTables.size(),
                        dr.convResults));

                if (dr.stoppedEarly) {
                    progress.clear();
                    if (dr.conflictStop()) {
                        Log.error(log, "Stopped early: table name conflict", "db", dbName);
                    } else {
                        Log.warn(log, "Stopped early (continueOnError=false)", "db", dbName);
                    }
                    stoppedEarlyGlobal = true;
                    stoppedByConflict = dr.conflictStop();
                    break;
                }
                if (dryRun && dr.sqlFile() != null) {
                    Log.info(log, "Dry-run output written", "db", dbName, "file", dr.sqlFile().toString());
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
            Map<String, ConversionResult> convResults, Path sqlFile) {}

    private DbResult migrateOneDb(Connection ssConn, Connection tidbConn,
                                  String dbName, List<String> databases, List<String> tables,
                                  boolean dropIfExists, boolean continueOnError,
                                  boolean dryRun) throws Exception {
        List<String[]> tableList = extractor.listTables(ssConn, tables);
        Log.info(log, "Starting conversion", "db", dbName, "tables", tableList.size());

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
            Log.error(log, "Table name conflict", "db", dbName);
            conflicts.forEach(line -> Log.error(log, "conflict", "tables", line));
            return new DbResult(1, 0, 0, true, true, tableList, List.of(), Map.of(), null);
        }

        int total = tableList.size();
        int succeeded = 0, warned = 0, failed = 0, skipped = 0;
        List<String[]> succeededTables = new ArrayList<>();
        Map<String, ConversionResult> convResults = new LinkedHashMap<>();
        boolean stopEarly = false;
        StringBuilder dryRunDdl = dryRun ? new StringBuilder() : null;

        for (int i = 0; i < total; i++) {
            String[] entry      = tableList.get(i);
            String schemaName   = entry[0];
            String tableName    = entry[1];
            String fullName     = schemaName + "." + tableName;

            if (!dryRun) progress.print(dbName, i, total, fullName);
            ConversionResult result = new ConversionResult(fullName);

            try {
                TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);
                if (ddl == null) {
                    // result already has ERROR set by converter
                } else if (dryRun) {
                    dryRunDdl.append("-- ").append(fullName).append("\n");
                    dryRunDdl.append(ddl).append("\n");
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

            // Log per-table conversion result — tree style
            switch (result.getStatus()) {
                case OK -> Log.info(log, fullName, "status", "OK");
                case WARN -> {
                    List<String> warnings = result.getWarnings();
                    Log.warn(log, fullName, "status", "WARN (" + warnings.size() + ")");
                    for (int j = 0; j < warnings.size(); j++) {
                        String prefix = (j == warnings.size() - 1) ? "  └─ " : "  ├─ ";
                        Log.warn(log, prefix + warnings.get(j));
                    }
                }
                case ERROR -> {
                    Log.error(log, fullName, "status", "ERROR");
                    Log.error(log, "  └─ " + result.getErrorMessage());
                }
                case SKIP -> {
                    Log.info(log, fullName, "status", "SKIP");
                    Log.info(log, "  └─ " + result.getErrorMessage());
                }
            }

            if (stopEarly) break;
        }

        if (!dryRun) {
            progress.print(dbName, total, total, "");
        }

        Log.info(log, "Database complete", "db", dbName,
                "total", total, "ok", succeeded - warned, "warn", warned,
                "error", failed, "skip", skipped);

        // Write SQL file for dry-run
        Path sqlFile = null;
        if (dryRun && dryRunDdl != null && !dryRunDdl.isEmpty()) {
            sqlFile = writeDryRunFile(dbName, dryRunDdl.toString());
        }

        return new DbResult(failed, warned, skipped, stopEarly, false,
                tableList, succeededTables, convResults, sqlFile);
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrl(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + escapeBacktick(dbName) + "`");
            st.execute("USE `" + escapeBacktick(dbName) + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }

}
