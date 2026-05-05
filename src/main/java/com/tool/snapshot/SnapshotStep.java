package com.tool.snapshot;

import static com.tool.common.SqlUtils.escapeBacktick;

import com.tool.common.FilterUtils;
import com.tool.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        Double snapshotMaxThreadsMultiplier = ctx.get("snapshotMaxThreadsMultiplier", Double.class);
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
        if (snapshotMaxThreadsMultiplier != null) snapshotConfig = snapshotConfig.withSnapshotMaxThreadsMultiplier(snapshotMaxThreadsMultiplier);

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
        Log.info(log, "database discovery", "count", dbNames.size(), "databases", dbNames);

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
        List<SnapshotDbResult> dbResults = new ArrayList<>();
        long totalRows = 0L;
        long startMs = System.currentTimeMillis();

        for (String dbName : dbNames) {
            long dbStartMs = System.currentTimeMillis();
            SnapshotDbResult dbResult = snapshotDatabase(
                    dbName, tables, cdcChecker, snapshotConfig);
            dbResults.add(dbResult);
            if (!dbResult.isError()) {
                totalRows += dbResult.rows();
            }
            long dbMs = System.currentTimeMillis() - dbStartMs;
            printDbResult(dbName, dbResult, dbMs);
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
        List<String> errors = new ArrayList<>();
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
                                errors.add(full + " — not empty");
                            }
                        } catch (java.sql.SQLException e) {
                            if ("42S02".equals(e.getSQLState())) {
                                errors.add(full + " — table does not exist in TiDB (run schema first)");
                            } else {
                                errors.add(full + " — " + e.getMessage());
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
            for (String err : errors) {
                Log.error(log, "  " + err);
            }
            return StepResult.fatal("target tables not empty or missing — run schema first, or clean target");
        }
        return null;
    }

    private SnapshotDbResult snapshotDatabase(String dbName, List<String> tables,
                                               CdcProvider cdcChecker,
                                               SnapshotConfig snapshotConfig) {
        try (Connection conn = DriverManager.getConnection(
                sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {

            List<String[]> tableList = sourceDriver.schemaExtractor().listTables(conn, tables);
            if (tables != null && !tables.isEmpty() && tableList.isEmpty()) {
                Log.warn(log, "--tables filter matched nothing, check spelling",
                        "database", dbName, "filter", tables);
            }
            CdcProvider.CdcCheckResult cdcResult = cdcChecker.check(conn, dbName, tableList);
            if (cdcResult.hasError()) {
                return new SnapshotDbResult(dbName, 0, 0L,
                        Instant.now(), cdcResult.errorMessage());
            }

            TiDBBatchWriter batchWriter = new TiDBBatchWriter(
                    targetDs, new SinkRecordConverter(targetDs), snapshotConfig.batchInsertSize(),
                    snapshotConfig.snapshotMaxThreads());
            Map<String, Long> estimates = sourceDriver.schemaExtractor().estimateRowCounts(conn, tableList);
            batchWriter.setTableEstimates(estimates);
            Log.info(log, "row estimates", "database", dbName, "estimates", estimates.toString());
            SnapshotSink sink = new SnapshotSink(batchWriter);

            DebeziumEngineFactory factory = new DebeziumEngineFactory(
                    config.getSource(), sourceDriver.debeziumConnectorClass());
            Log.info(log, "snapshot starting", "database", dbName, "tables", tableList.size());

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> engineError = new AtomicReference<>();

            DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                    dbName, snapshotConfig, tableList, sink, done::countDown);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            long finalRows = 0L;
            try {
                executor.submit(() -> {
                    try {
                        engine.run();
                    } catch (Throwable t) {
                        engineError.set(t);
                    } finally {
                        done.countDown();
                    }
                });
                // ORDERED guarantees "snapshot=last" arrives after all data.
                // Poll isSnapshotComplete() — reliable signal, no idle timeout needed.
                long startMs = System.currentTimeMillis();
                while (!done.await(2, TimeUnit.SECONDS)) {
                    if (sink.isSnapshotComplete()) {
                        long rows = batchWriter.getTotalRows();
                        Log.info(log, "snapshot finished", "database", dbName, "rows", rows);
                        long tClose = System.currentTimeMillis();
                        engine.close();
                        Log.info(log, "engine.close() done", "database", dbName, "ms", System.currentTimeMillis() - tClose);
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
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                long t1 = System.currentTimeMillis();
                sink.logTableCounts();
                finalRows = batchWriter.getTotalRows();
                Log.info(log, "snapshot data loaded, flushing remaining writes",
                        "database", dbName);
                batchWriter.flushAll();
                long t2 = System.currentTimeMillis();
                Log.info(log, "snapshot shutdown", "db", dbName,
                        "executorShutdownMs", t1 - t0, "flushMs", t2 - t1);
            }

            return new SnapshotDbResult(dbName, tableList.size(),
                    finalRows, Instant.now(), null);
        } catch (Exception e) {
            Log.error(log, "database snapshot error", "database", dbName, "error", e.getMessage(),
                    "exceptionType", e.getClass().getName());
            // Log root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            Log.error(log, "root cause", "database", dbName, "type", cause.getClass().getName(), "message", cause.getMessage());
            // Full stack trace as structured fields (one frame per field for greppability)
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 20); i++) {
                Log.error(log, "stack", "database", dbName, "frame", frames[i].toString());
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
                Log.info(log, "snapshot results written to database",
                        "databases", dbResults.size(), "totalRows", totalRows);
            }
        } catch (Exception e) {
            Log.warn(log, "failed to write snapshot results", "error", e.getMessage());
        }
    }

    private void printDbResult(String dbName, SnapshotDbResult r, long elapsedMs) {
        if (r.isError()) {
            Log.error(log, "database snapshot failed", "database", dbName, "error", r.error());
        } else {
            Log.info(log, "database snapshot done", "database", dbName,
                    "tables", r.tables(), "rows", r.rows(), "ms", elapsedMs);
        }
    }
}
