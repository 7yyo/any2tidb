package com.tool.pipeline.steps;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.Warning;
import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.common.FilterUtils;
import com.tool.schema.writer.SchemaWriter;
import com.tool.source.SourceDriver;
import com.tool.task.TaskManager;

import static com.tool.common.SqlUtils.escapeBacktick;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.MDC;

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
    private final SchemaWriter writer;
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrateStep.class);
    private final ProgressReporter progress;

    public SchemaMigrateStep(AppConfig config, SchemaExtractor extractor,
                             SchemaWriter writer, ProgressReporter progress,
                             SourceDriver sourceDriver) {
        this.config    = config;
        this.extractor = extractor;
        this.writer    = writer;
        this.progress  = progress;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "SchemaMigrate"; }

    /** Collects all DDL for one database and writes it to a .sql file. Returns the file path. */
    private Path writeDryRunFile(String dbName, String ddlBlock, StepContext ctx) throws IOException {
        String filename = dbName + ".sql";
        TaskManager tm = ctx.get("taskManager", TaskManager.class);
        String taskName = ctx.get("taskName", String.class);
        Path out = (tm != null && taskName != null)
                ? tm.getTaskDir(taskName).resolve(filename)
                : Path.of(filename);
        try (PrintWriter pw = new PrintWriter(out.toFile(), StandardCharsets.UTF_8)) {
            pw.println("-- any2tidb dry-run  db=" + dbName);
            pw.println("-- Source to TiDB directly: mysql -h host -P 4000 -u root < " + filename);
            pw.println();
            pw.println("SET tidb_multi_statement_mode = 'ON';");
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

        List<String> dbNames;
        try (Connection masterConn = DriverManager.getConnection(
                sourceDriver.buildJdbcUrl(config.getSource()),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = extractor.listDatabases(masterConn);
        }
        int totalDbs = dbNames.size();
        dbNames = FilterUtils.filterNames(dbNames, databases);
        if (databases != null && !databases.isEmpty()) {
            Log.info(log, "Filtered databases", "before", totalDbs, "after", dbNames.size(),
                    "filter", databases);
            if (dbNames.isEmpty()) {
                Log.warn(log, "--databases filter matched nothing, check spelling",
                        "filter", databases);
            }
        }
        Log.info(log, "Found databases", "count", dbNames.size());
        progress.setDbNameWidth(dbNames.stream().mapToInt(String::length).max().orElse(8));

        Integer dbThreadsObj = ctx.get("schemaDbThreads", Integer.class);
        int dbThreads = (dbThreadsObj != null && dbThreadsObj > 1) ? dbThreadsObj : 1;

        int totalFailed = 0;
        List<SummaryPrinter.DbSummary> summaries = new ArrayList<>();

        if (dbThreads <= 1) {
            // Serial path
            for (String dbName : dbNames) {
                try (Connection ssConn = DriverManager.getConnection(
                         sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                         config.getSource().getUsername(),
                         config.getSource().getPassword());
                     Connection tidbConn = dryRun ? null : openTiDB(dbName)) {

                    DbResult dr = migrateOneDb(ssConn, tidbConn, dbName,
                            databases, tables, dropIfExists, dryRun, ctx);
                    totalFailed += dr.failed;

                    summaries.add(new SummaryPrinter.DbSummary(
                            dbName,
                            dr.allTables.size(),
                            dr.succeededTables.size(),
                            dr.convResults));

                    if (dr.conflictStop()) {
                        progress.clear();
                        Log.error(log, "Stopped early: table name conflict", "db", dbName);
                        ctx.put("dbSummaries", summaries);
                        ctx.put("totalFailed", totalFailed);
                        return StepResult.fatal("stopped early: table name conflict");
                    }
                    if (dryRun && dr.sqlFile() != null) {
                        Log.info(log, "Dry-run output written", "db", dbName, "file", dr.sqlFile().toString());
                    }
                }
            }
        } else {
            // Concurrent path — one thread per database
            ConcurrentLinkedQueue<SummaryPrinter.DbSummary> summaryQueue = new ConcurrentLinkedQueue<>();
            AtomicInteger failed = new AtomicInteger(0);
            AtomicInteger conflicts = new AtomicInteger(0);
            String taskName = ctx.get("taskName", String.class);
            ExecutorService pool = Executors.newFixedThreadPool(dbThreads, r -> {
                Thread t = new Thread(r, "schema-db-worker");
                t.setDaemon(true);
                return t;
            });
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String dbName : dbNames) {
                tasks.add(() -> {
                    MDC.put("task", taskName);
                    try {
                        try (Connection ssConn = DriverManager.getConnection(
                                 sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                                 config.getSource().getUsername(),
                                 config.getSource().getPassword());
                             Connection tidbConn = dryRun ? null : openTiDB(dbName)) {

                            DbResult dr = migrateOneDb(ssConn, tidbConn, dbName,
                                    databases, tables, dropIfExists, dryRun, ctx);
                            failed.addAndGet(dr.failed);
                            if (dr.conflictStop()) {
                                conflicts.incrementAndGet();
                            }
                            summaryQueue.add(new SummaryPrinter.DbSummary(
                                    dbName,
                                    dr.allTables.size(),
                                    dr.succeededTables.size(),
                                    dr.convResults));
                            if (dryRun && dr.sqlFile() != null) {
                                Log.info(log, "Dry-run output written", "db", dbName, "file", dr.sqlFile().toString());
                            }
                        }
                    } finally {
                        MDC.remove("task");
                    }
                    return null;
                });
            }
            try {
                pool.invokeAll(tasks);
            } finally {
                pool.shutdown();
            }
            summaries.addAll(summaryQueue);
            totalFailed = failed.get();
            if (conflicts.get() > 0) {
                progress.clear();
                Log.error(log, "Stopped early: table name conflict", "databases", conflicts.get());
                ctx.put("dbSummaries", summaries);
                ctx.put("totalFailed", totalFailed);
                return StepResult.fatal("stopped early: table name conflict in " + conflicts.get() + " database(s)");
            }
        }

        ctx.put("dbSummaries", summaries);
        ctx.put("totalFailed", totalFailed);

        return StepResult.ok("schema migration complete, failed=" + totalFailed);
    }

    // ── Internal result carrier ───────────────────────────────────────────────

    private record DbResult(
            int failed, int warned, int skipped, boolean conflictStop,
            List<String[]> allTables, List<String[]> succeededTables,
            Map<String, ConversionResult> convResults, Path sqlFile) {}

    private record TableResult(String fullName, ConversionResult result) {}

    private void emitTableBlock(TableResult t, String dbName) {
        ConversionResult result = t.result();
        String fullName = t.fullName();
        switch (result.getStatus()) {
            case OK -> Log.info(log, fullName, "db", dbName, "status", "OK");
            case WARN -> {
                List<Warning> warnings = result.getWarnings();
                Log.warn(log, fullName, "db", dbName, "status", "WARN", "warnings", warnings.size());
                for (int j = 0; j < warnings.size(); j++) {
                    Warning w = warnings.get(j);
                    String prefix = (j == warnings.size() - 1) ? "  └─ " : "  ├─ ";
                    Object[] flat = flatten(prefix + w.msg(), w.fields());
                    Log.warn(log, (String) flat[0],
                            java.util.Arrays.copyOfRange(flat, 1, flat.length));
                }
            }
            case ERROR -> {
                Log.error(log, fullName, "db", dbName, "status", "ERROR");
                Log.error(log, "  └─ " + result.getErrorMessage());
            }
            case SKIP -> {
                Log.info(log, fullName, "db", dbName, "status", "SKIP");
                Log.info(log, "  └─ " + result.getErrorMessage());
            }
        }
    }

    private DbResult migrateOneDb(Connection ssConn, Connection tidbConn,
                                  String dbName, List<String> databases, List<String> tables,
                                  boolean dropIfExists, boolean dryRun, StepContext ctx) throws Exception {
        List<String[]> tableList = extractor.listTables(ssConn, tables);
        if (tables != null && !tables.isEmpty() && tableList.isEmpty()) {
            Log.warn(log, "--tables filter matched nothing, check spelling",
                    "database", dbName, "filter", tables);
        }
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
            return new DbResult(1, 0, 0, true, tableList, List.of(), Map.of(), null);
        }

        int total = tableList.size();
        int succeeded = 0, warned = 0, failed = 0, skipped = 0;
        List<String[]> succeededTables = new ArrayList<>();
        Map<String, ConversionResult> convResults = new LinkedHashMap<>();
        StringBuilder dryRunDdl = dryRun ? new StringBuilder() : null;
        List<TableResult> completed = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            String[] entry      = tableList.get(i);
            String schemaName   = entry[0];
            String tableName    = entry[1];
            String fullName     = schemaName + "." + tableName;

            if (!dryRun) progress.print(dbName, i, total, fullName);
            ConversionResult result = new ConversionResult(fullName);

            try {
                String ddl = extractor.generateCreateTableDDL(
                        ssConn, schemaName, tableName, sourceDriver.typeMapper(), result, dropIfExists);
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
                case ERROR -> { failed++; convResults.put(fullName, result); }
                case SKIP  -> { skipped++; convResults.put(fullName, result); }
            }

            completed.add(new TableResult(fullName, result));
        }

        for (TableResult t : completed) {
            emitTableBlock(t, dbName);
        }

        // ── Views / Procedures / Functions / Triggers ──────────────────────
        // Only for sources that support them (MySQL). SQL Server returns empty lists.
        if (tidbConn != null || dryRun) {
            var nonTableFailures = migrateNonTableObjects(
                    ssConn, tidbConn, dbName, dryRun, dropIfExists, dryRunDdl);
            failed += nonTableFailures;
        }

        if (!dryRun) {
            progress.print(dbName, total, total, "");
        }

        // Summary: list all failed tables
        if (failed > 0) {
            Log.error(log, "Failures in database", "db", dbName, "count", failed);
            convResults.forEach((name, r) -> {
                if (r.getStatus() == ConversionResult.Status.ERROR)
                    Log.error(log, "  ├─ " + name + " — " + r.getErrorMessage());
            });
        }

        Log.info(log, "Database complete", "db", dbName,
                "total", total, "ok", succeeded - warned, "warn", warned,
                "error", failed, "skip", skipped);

        // Write SQL file for dry-run
        Path sqlFile = null;
        if (dryRun && dryRunDdl != null && !dryRunDdl.isEmpty()) {
            sqlFile = writeDryRunFile(dbName, dryRunDdl.toString(), ctx);
        }

        return new DbResult(failed, warned, skipped, false, tableList, succeededTables,
                convResults, sqlFile);
    }

    /**
     * Migrates views, procedures, functions, triggers for a single database.
     * Returns the count of failed objects.
     */
    private int migrateNonTableObjects(Connection srcConn, Connection tidbConn,
                                       String dbName, boolean dryRun,
                                       boolean dropIfExists, StringBuilder dryRunDdl) throws Exception {
        int failed = 0;

        record Obj(String type, java.util.function.Supplier<List<String>> lister,
                   QuadFunction<Connection, String, String, ConversionResult, String> generator) {}

        Obj[] objects = {
            new Obj("FUNCTION", () -> { try { return extractor.listFunctions(srcConn, dbName); }
                          catch (Exception e) { return List.of(); } },
                    (c, d, n, r) -> { try { return extractor.generateFunctionDDL(c, d, n, r); }
                          catch (Exception e) { r.setError(e.getMessage()); return null; } }),
            new Obj("PROCEDURE", () -> { try { return extractor.listProcedures(srcConn, dbName); }
                          catch (Exception e) { return List.of(); } },
                    (c, d, n, r) -> { try { return extractor.generateProcedureDDL(c, d, n, r); }
                          catch (Exception e) { r.setError(e.getMessage()); return null; } }),
            new Obj("TRIGGER", () -> { try { return extractor.listTriggers(srcConn, dbName); }
                          catch (Exception e) { return List.of(); } },
                    (c, d, n, r) -> { try { return extractor.generateTriggerDDL(c, d, n, r); }
                          catch (Exception e) { r.setError(e.getMessage()); return null; } }),
            new Obj("VIEW",   () -> { try { return extractor.listViews(srcConn, dbName); }
                          catch (Exception e) { return List.of(); } },
                    (c, d, n, r) -> { try { return extractor.generateViewDDL(c, d, n, r); }
                          catch (Exception e) { r.setError(e.getMessage()); return null; } }),
        };

        for (Obj obj : objects) {
            List<String> names = obj.lister.get();
            if (names.isEmpty()) continue;
            for (String name : names) {
                ConversionResult r = new ConversionResult(name);
                String ddl = obj.generator.apply(srcConn, dbName, name, r);
                if (ddl == null) {
                    failed++;
                    Log.error(log, "Non-table object failed", "type", obj.type, "db", dbName, "name", name, "status", "ERROR");
                    Log.error(log, "  └─ " + r.getErrorMessage());
                } else if (dryRun) {
                    dryRunDdl.append("-- ").append(obj.type).append(" ").append(name).append("\n");
                    dryRunDdl.append(ddl).append("\n");
                } else {
                    try {
                        writer.executeDDL(tidbConn, ddl, r);
                    } catch (Exception e) {
                        r.setError(e.getMessage());
                    }
                    if (r.getStatus() == ConversionResult.Status.ERROR) {
                        failed++;
                        Log.error(log, "Non-table object failed", "type", obj.type, "db", dbName, "name", name, "status", "ERROR");
                        Log.error(log, "  └─ " + r.getErrorMessage());
                    } else {
                        Log.info(log, "Non-table object migrated", "type", obj.type, "name", name, "status", "OK");
                    }
                }
            }
        }
        return failed;
    }

    private static Object[] flatten(String msg, java.util.Map<String, String> fields) {
        Object[] flat = new Object[1 + fields.size() * 2];
        flat[0] = msg;
        int i = 1;
        for (var e : fields.entrySet()) {
            flat[i++] = e.getKey();
            flat[i++] = e.getValue();
        }
        return flat;
    }

    @FunctionalInterface
    private interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrl(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + escapeBacktick(dbName) + "`");
            st.execute("USE `" + escapeBacktick(dbName) + "`");
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("SET tidb_multi_statement_mode = 'ON'");
        }
        conn.setCatalog(dbName);
        return conn;
    }

}
