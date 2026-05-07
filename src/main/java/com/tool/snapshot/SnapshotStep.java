package com.tool.snapshot;

import static com.tool.common.SqlUtils.escapeBacktick;

import com.tool.common.FilterUtils;
import com.tool.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.tool.logging.Log;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.source.CdcProvider;
import com.tool.source.SourceDriver;
import com.tool.task.TaskManager;
import com.tool.snapshot.engine.DebeziumEngineFactory;
import com.tool.snapshot.model.SnapshotDbResult;
import com.tool.snapshot.sink.SinkRecordConverter;
import com.tool.snapshot.sink.SnapshotSink;
import com.tool.snapshot.sink.TiDBBatchWriter;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class SnapshotStep implements MigrationStep {

    private static final long ENGINE_TIMEOUT_MINUTES = 30;

    private final AppConfig config;
    private final DataSource targetDs;
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(SnapshotStep.class);

    public SnapshotStep(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
        this.config = config;
        this.targetDs = targetDs;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "Snapshot"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        List<String> tables = ctx.get("tables", List.class);
        List<String> databases = ctx.get("databases", List.class);
        Integer batchSize = ctx.get("batchSize", Integer.class);
        Integer fetchSize = ctx.get("fetchSize", Integer.class);
        Integer snapshotThreads = ctx.get("snapshotThreads", Integer.class);
        Integer maxQueueSize = ctx.get("maxQueueSize", Integer.class);
        Integer pollIntervalMs = ctx.get("pollIntervalMs", Integer.class);
        Integer offsetCommitIntervalMs = ctx.get("offsetCommitIntervalMs", Integer.class);
        String offsetStoragePath = ctx.get("offsetStoragePath", String.class);
        String schemaHistoryPath = ctx.get("schemaHistoryPath", String.class);

        SnapshotConfig snapshotConfig = SnapshotConfig.defaults();
        if (offsetStoragePath != null) snapshotConfig = snapshotConfig.withOffsetStoragePath(offsetStoragePath);
        if (schemaHistoryPath != null) snapshotConfig = snapshotConfig.withSchemaHistoryPath(schemaHistoryPath);
        if (batchSize != null) snapshotConfig = snapshotConfig.withBatchInsertSize(batchSize);
        if (fetchSize != null) snapshotConfig = snapshotConfig.withSnapshotFetchSize(fetchSize);
        if (snapshotThreads != null) snapshotConfig = snapshotConfig.withSnapshotMaxThreads(snapshotThreads);
        if (maxQueueSize != null) snapshotConfig = snapshotConfig.withMaxQueueSize(maxQueueSize);
        if (pollIntervalMs != null) snapshotConfig = snapshotConfig.withPollIntervalMs(pollIntervalMs);
        if (offsetCommitIntervalMs != null) snapshotConfig = snapshotConfig.withOffsetCommitIntervalMs(offsetCommitIntervalMs);
        new File(snapshotConfig.offsetStoragePath()).mkdirs();
        new File(snapshotConfig.schemaHistoryPath()).mkdirs();

        List<String> dbNames;
        try (Connection master = DriverManager.getConnection(
                sourceDriver.buildJdbcUrl(config.getSource()),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = sourceDriver.schemaExtractor().listDatabases(master);
        } catch (Exception e) {
            return StepResult.fatal("Cannot connect to source database: " + e.getMessage());
        }
        Log.info(log, "database discovery", "count", dbNames.size());

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
        if (dbNames.isEmpty()) {
            return StepResult.ok("no databases to snapshot");
        }

        // Pre-check: target TiDB tables must be empty
        StepResult emptyCheck = checkTargetTablesEmpty(dbNames, tables, config);
        if (emptyCheck != null) return emptyCheck;

        // Check for stale offset/history files from previous snapshots
        List<String> staleDbs = new ArrayList<>();
        for (String dbName : dbNames) {
            Path offsetFile = Path.of(snapshotConfig.offsetStoragePath(), dbName + ".offset");
            Path historyFile = Path.of(snapshotConfig.schemaHistoryPath(), dbName + ".history");
            if (Files.exists(offsetFile) || Files.exists(historyFile)) {
                staleDbs.add(dbName);
            }
        }
        if (!staleDbs.isEmpty()) {
            System.out.println("\nFound stale snapshot data for " + staleDbs.size()
                    + " database(s): " + String.join(", ", staleDbs));
            System.out.print("Delete and start fresh? [y/N] ");
            String line = System.console().readLine();
            if (line == null || !line.trim().equalsIgnoreCase("y")) {
                return StepResult.ok("Snapshot cancelled.");
            }
            int deleted = 0;
            for (String dbName : staleDbs) {
                try { Files.deleteIfExists(Path.of(snapshotConfig.offsetStoragePath(), dbName + ".offset")); deleted++; } catch (Exception ignored) {}
                try { Files.deleteIfExists(Path.of(snapshotConfig.schemaHistoryPath(), dbName + ".history")); deleted++; } catch (Exception ignored) {}
            }
            Log.info(log, "Cleaned stale snapshot files", "count", deleted);
        }

        CdcProvider cdcChecker = sourceDriver.cdcProvider();
        String taskName = ctx.get("taskName", String.class);
        Integer dbThreadsObj = ctx.get("snapshotDbThreads", Integer.class);
        int dbThreads = (dbThreadsObj != null && dbThreadsObj > 1) ? dbThreadsObj : 1;
        final SnapshotConfig finalSnapshotConfig = snapshotConfig;

        List<SnapshotDbResult> dbResults;
        long totalRows;
        long startMs = System.currentTimeMillis();

        if (dbThreads <= 1) {
            // Serial path — unchanged
            dbResults = new ArrayList<>();
            totalRows = 0L;
            for (String dbName : dbNames) {
                long dbStartMs = System.currentTimeMillis();
                SnapshotDbResult dbResult = snapshotDatabase(
                        dbName, tables, cdcChecker, finalSnapshotConfig, taskName);
                dbResults.add(dbResult);
                if (!dbResult.isError()) {
                    totalRows += dbResult.rows();
                }
                long dbMs = System.currentTimeMillis() - dbStartMs;
                printDbResult(dbName, dbResult, dbMs);
            }
        } else {
            // Concurrent path — one thread per database
            ConcurrentLinkedQueue<SnapshotDbResult> results = new ConcurrentLinkedQueue<>();
            AtomicLong rows = new AtomicLong(0L);
            ExecutorService pool = Executors.newFixedThreadPool(dbThreads, r -> {
                Thread t = new Thread(r, "snapshot-db-worker");
                t.setDaemon(true);
                return t;
            });
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String dbName : dbNames) {
                tasks.add(() -> {
                    MDC.put("task", taskName);
                    try {
                        long dbStartMs = System.currentTimeMillis();
                        SnapshotDbResult dbResult = snapshotDatabase(
                                dbName, tables, cdcChecker, finalSnapshotConfig, taskName);
                        results.add(dbResult);
                        if (!dbResult.isError()) {
                            rows.addAndGet(dbResult.rows());
                        }
                        long dbMs = System.currentTimeMillis() - dbStartMs;
                        printDbResult(dbName, dbResult, dbMs);
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
                pool.awaitTermination(5, TimeUnit.MINUTES);
            }
            dbResults = new ArrayList<>(results);
            totalRows = rows.get();
        }

        long totalMs = System.currentTimeMillis() - startMs;
        Log.info(log, "snapshot complete", "databases", dbResults.size(),
                "rows", totalRows, "ms", totalMs);

        ctx.put("snapshotSummaries", dbResults);
        ctx.put("snapshotTotalRows", totalRows);
        writeSnapshotMeta(dbResults, totalRows, ctx);

        if (SnapshotDbResult.shouldBlockPipeline(dbResults)) {
            return StepResult.fatal("snapshot completed with errors");
        }
        return StepResult.ok("snapshot complete, databases=" + dbResults.size() + " rows=" + totalRows);
    }

    private StepResult checkTargetTablesEmpty(List<String> dbNames, List<String> tables,
                                               AppConfig config) {
        List<String[]> errors = new ArrayList<>();
        for (String dbName : dbNames) {
            try (Connection srcConn = DriverManager.getConnection(
                    sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                    config.getSource().getUsername(),
                    config.getSource().getPassword())) {
                List<String[]> tableList = sourceDriver.schemaExtractor().listTables(srcConn, tables);
                if (tableList.isEmpty()) continue;
                try (Connection tidbConn = DriverManager.getConnection(
                        config.getTarget().tidbJdbcUrl(),
                        config.getTarget().getUsername(),
                        config.getTarget().getPassword())) {
                    for (String[] entry : tableList) {
                        String tableName = entry[1];
                        String full = dbName + "." + tableName;
                        try (java.sql.Statement st = tidbConn.createStatement();
                             java.sql.ResultSet rs = st.executeQuery(
                                     "SELECT 1 FROM `" + escapeBacktick(dbName) + "`.`" + escapeBacktick(tableName) + "` LIMIT 1")) {
                            if (rs.next()) {
                                errors.add(new String[]{full, "not empty"});
                            }
                        } catch (java.sql.SQLException e) {
                            if ("42S02".equals(e.getSQLState())) {
                                errors.add(new String[]{full, "table does not exist in TiDB (run schema first)"});
                            } else {
                                errors.add(new String[]{full, e.getMessage()});
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.warn(log, "failed to check target tables for " + dbName, "error", e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            Log.error(log, "Target TiDB pre-check failed", "count", errors.size());
            for (String[] err : errors) {
                Log.error(log, "target table not ready", "table", err[0], "error", err[1]);
            }
            return StepResult.fatal("target tables not empty or missing — run schema first, or clean target");
        }
        return null;
    }

    private SnapshotDbResult snapshotDatabase(String dbName, List<String> tables,
                                               CdcProvider cdcChecker,
                                               SnapshotConfig snapshotConfig,
                                               String taskName) {
        try (Connection conn = DriverManager.getConnection(
                sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {

            List<String[]> tableList = sourceDriver.schemaExtractor().listTables(conn, tables);
            if (tables != null && !tables.isEmpty() && tableList.isEmpty()) {
                Log.warn(log, "--tables filter matched nothing, check spelling",
                        "db", dbName, "filter", tables);
            }
            CdcProvider.CdcCheckResult cdcResult = cdcChecker.check(conn, dbName, tableList);
            if (cdcResult.hasError()) {
                return new SnapshotDbResult(dbName, 0, 0L,
                        Instant.now(), cdcResult.errorMessage());
            }

            TiDBBatchWriter batchWriter = new TiDBBatchWriter(
                    targetDs, new SinkRecordConverter(targetDs), snapshotConfig.batchInsertSize(),
                    snapshotConfig.snapshotMaxThreads(), dbName);
            Map<String, Long> estimates = sourceDriver.schemaExtractor().estimateRowCounts(conn, tableList);
            batchWriter.setTableEstimates(estimates);
            Log.info(log, "snapshot starting", "db", dbName,
                    "tables", tableList.size(), "totalRows",
                    estimates.values().stream().mapToLong(v -> v).sum());
            for (int i = 0; i < tableList.size(); i++) {
                String[] t = tableList.get(i);
                String fullName = t[0] + "." + t[1];
                String prefix = (i == tableList.size() - 1) ? "  └─ " : "  ├─ ";
                Log.info(log, prefix + fullName, "rows", estimates.getOrDefault(t[1], 0L));
            }

            SnapshotSink sink = new SnapshotSink(batchWriter);

            // If every table is empty, skip Debezium engine entirely.
            // Debezium SQL Server connector hangs on all-empty databases (no completion signal).
            if (allTablesEmpty(conn, tableList, sourceDriver.type())) {
                Log.info(log, "all tables empty, skipping debezium engine", "db", dbName);
                writeEmptyDbOffsets(dbName, snapshotConfig);
                return new SnapshotDbResult(dbName, tableList.size(), 0L, Instant.now(), null);
            }

            DebeziumEngineFactory factory = new DebeziumEngineFactory(
                    config.getSource(), sourceDriver);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> engineError = new AtomicReference<>();

            DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                    dbName, snapshotConfig, tableList, sink, taskName, done::countDown);

            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "engine-" + dbName);
                t.setDaemon(true);
                return t;
            });
            long finalRows = 0L;
            try {
                executor.submit(() -> {
                    MDC.put("task", taskName);
                    try {
                        engine.run();
                    } catch (Throwable t) {
                        engineError.set(t);
                    } finally {
                        done.countDown();
                        MDC.remove("task");
                    }
                });
                long startMs = System.currentTimeMillis();
                while (!done.await(2, TimeUnit.SECONDS)) {
                    if (sink.isSnapshotComplete()) {
                        engine.close();
                        if (!done.await(30, TimeUnit.SECONDS)) {
                            Log.warn(log, "engine.run() did not return within 30s after close",
                                    "db", dbName);
                        }
                        break;
                    }
                    if (System.currentTimeMillis() - startMs > TimeUnit.MINUTES.toMillis(ENGINE_TIMEOUT_MINUTES)) {
                        return new SnapshotDbResult(dbName, tableList.size(),
                                batchWriter.getTotalRows(), Instant.now(),
                                "Snapshot timed out after " + ENGINE_TIMEOUT_MINUTES + " minutes");
                    }
                }
                if (engineError.get() != null) {
                    return new SnapshotDbResult(dbName, 0, 0L,
                            Instant.now(), "Engine error: " + engineError.get().getMessage());
                }
            } finally {
                long t0 = System.currentTimeMillis();
                executor.shutdown();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    Log.warn(log, "snapshot executor did not exit within 30s, forcing shutdown",
                            "db", dbName);
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
                long t1 = System.currentTimeMillis();
                sink.logTableCounts();
                finalRows = batchWriter.getTotalRows();
                Log.info(log, "snapshot finished, flushing",
                        "db", dbName, "totalRows", finalRows,
                        "pendingRows", batchWriter.getPendingWrites());
                batchWriter.flushAll();
                long t2 = System.currentTimeMillis();
                Log.info(log, "snapshot finished, flushed", "db", dbName,
                        "flushMs", t2 - t1);
            }

            return new SnapshotDbResult(dbName, tableList.size(),
                    finalRows, Instant.now(), null);
        } catch (Exception e) {
            Log.error(log, "database snapshot error", "db", dbName, "error", e.getMessage(),
                    "exceptionType", e.getClass().getName());
            // Log root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            Log.error(log, "root cause", "db", dbName, "type", cause.getClass().getName(), "message", cause.getMessage());
            // Full stack trace as structured fields (one frame per field for greppability)
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 20); i++) {
                Log.error(log, "stack", "db", dbName, "frame", frames[i].toString());
            }
            return new SnapshotDbResult(dbName, 0, 0L,
                    Instant.now(), e.getMessage());
        }
    }

    private void writeSnapshotMeta(List<SnapshotDbResult> dbResults, long totalRows, StepContext ctx) {
        try {
            TaskManager tm = ctx.get("taskManager", TaskManager.class);
            String taskName = ctx.get("taskName", String.class);
            if (tm != null && taskName != null) {
                List<TaskManager.SnapshotResult> rows = dbResults.stream()
                        .map(db -> new TaskManager.SnapshotResult(
                                db.dbName(), db.tables(), db.rows(),
                                db.isError() ? db.error() : null))
                        .toList();
                tm.writeSnapshotResults(taskName, rows);
            }
        } catch (Exception e) {
            Log.warn(log, "failed to write snapshot results", "error", e.getMessage());
        }
    }

    /**
     * Check whether every table in the list has zero rows.
     * Used to skip Debezium engine for all-empty databases (known Debezium 3.5 hang).
     */
    private static boolean allTablesEmpty(Connection conn, List<String[]> tableList, String sourceType) {
        boolean sqlserver = "sqlserver".equals(sourceType);
        for (String[] entry : tableList) {
            String schema = entry[0];
            String table = entry[1];
            String sql = sqlserver
                    ? "SELECT TOP 1 1 FROM [" + schema + "].[" + table + "]"
                    : "SELECT 1 FROM `" + schema + "`.`" + table + "` LIMIT 1";
            try (Statement st = conn.createStatement();
                 var rs = st.executeQuery(sql)) {
                if (rs.next()) return false; // at least one row found
            } catch (Exception ignored) {
                // If the query fails, assume non-empty to stay safe
                return false;
            }
        }
        return true;
    }

    /**
     * Write offset and minimal schema history for an empty database so sync can
     * resume CDC from here. Debezium needs a history file even for empty databases
     * — otherwise the sync engine fails with "db history topic is missing".
     */
    private void writeEmptyDbOffsets(String dbName, SnapshotConfig snapshotConfig) {
        try {
            String hexLsn = sourceDriver.captureCdcStartPoint(dbName);
            if (hexLsn == null) {
                Log.warn(log, "no LSN captured for empty database, sync cannot resume from this point",
                        "db", dbName);
                return;
            }
            String debeziumLsn = com.tool.source.sqlserver.SqlServerCdcUtils.hexLsnToDebezium(hexLsn);
            String offsetPath = snapshotConfig.offsetStoragePath() + "/" + dbName + ".offset";
            String historyPath = snapshotConfig.schemaHistoryPath() + "/" + dbName + ".history";
            com.tool.source.sqlserver.SqlServerCdcUtils.writeDebeziumOffset(offsetPath, dbName, debeziumLsn);
            writeMinimalHistory(historyPath, dbName, debeziumLsn);
            Log.info(log, "offset written for empty database", "db", dbName,
                    "lsn", debeziumLsn);
        } catch (Exception e) {
            Log.warn(log, "failed to write offset for empty database, sync cannot resume",
                    "db", dbName, "error", e.getMessage());
        }
    }

    /** Write a minimal schema history record so sync can start for empty databases. */
    private static void writeMinimalHistory(String path, String dbName, String lsn) {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).getParent());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.putObject("source")
                    .put("server", "any2tidb_" + dbName)
                    .put("database", dbName);
            node.putObject("position")
                    .put("event_serial_no", 1)
                    .put("commit_lsn", lsn)
                    .put("change_lsn", "NULL")
                    .put("snapshot", "INITIAL")
                    .put("snapshot_completed", true);
            node.put("ts_ms", System.currentTimeMillis());
            node.put("databaseName", dbName);
            node.put("schemaName", "dbo");
            node.putArray("tableChanges");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.FileWriter(path, java.nio.charset.StandardCharsets.UTF_8))) {
                pw.println(mapper.writeValueAsString(node));
            }
        } catch (Exception e) {
            Log.warn(LoggerFactory.getLogger(SnapshotStep.class),
                    "failed to write minimal schema history", "db", dbName, "error", e.getMessage());
        }
    }

    /**
     * Force ALL relevant loggers to DEBUG/TRACE so engine.run() internals are visible.
     * Covers Debezium (SLF4J/Logback), SQL Server JDBC driver (JUL), and HikariCP.
     * Returns a diagnostic string.
     */
    private void printDbResult(String dbName, SnapshotDbResult r, long elapsedMs) {
        if (r.isError()) {
            Log.error(log, "database snapshot failed", "db", dbName, "error", r.error());
        } else {
            Log.info(log, "database snapshot done", "db", dbName,
                    "tables", r.tables(), "rows", r.rows(), "ms", elapsedMs);
        }
    }
}
