package com.tool.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.snapshot.model.SnapshotDbResult;
import com.tool.snapshot.sink.SinkRecordConverter;
import com.tool.source.SourceDriver;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import javax.sql.DataSource;
import static com.tool.source.sqlserver.SqlServerCdcUtils.patchOffsetLsn;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SyncStep implements MigrationStep {

    private final AppConfig config;
    private final DataSource targetDs;
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(SyncStep.class);

    public SyncStep(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
        this.config = config;
        this.targetDs = targetDs;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "Sync"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        SyncConfig syncConfig = ctx.get("syncConfig", SyncConfig.class);
        final SyncConfig cfg = syncConfig != null ? syncConfig : SyncConfig.defaults();

        // 1. Read snapshot results — prefer ctx, then DB, fallback to meta file
        List<SnapshotDbResult> dbResults = ctx.get("snapshotSummaries", List.class);
        if (dbResults == null || dbResults.isEmpty()) {
            dbResults = readSnapshotFromDb(ctx);
        }
        if (dbResults.isEmpty()) {
            dbResults = readSnapshotMetaFile(cfg.metaFile());
        }
        if (dbResults.isEmpty()) {
            return StepResult.fatal("No snapshot results found. Run snapshot first, then point sync to it with --from-task.");
        }

        // 2. Filter out failed databases
        List<SnapshotDbResult> okDbs = dbResults.stream()
                .filter(r -> !r.isError())
                .toList();
        if (okDbs.isEmpty()) {
            return StepResult.fatal("All databases failed in snapshot. Cannot sync.");
        }

        // 3. Verify offset files and log sync plan
        boolean allOffsetsOk = true;
        for (SnapshotDbResult db : okDbs) {
            String offsetPath = cfg.offsetStoragePath() + "/" + db.dbName() + ".offset";
            OffsetInfo info = readOffsetInfo(offsetPath, db.dbName());
            if (info == null || !info.snapshotCompleted) {
                Log.error(log, "offset not ready for sync",
                        "db", db.dbName(),
                        "offsetPath", offsetPath,
                        "found", info != null,
                        "snapshotCompleted", info != null ? info.snapshotCompleted : false);
                allOffsetsOk = false;
            } else {
                // Also verify LSN is still within CDC retention window
                if (info.commitLsn != null && !lsnInCdcWindow(db.dbName(), info.commitLsn)) {
                    Log.error(log, "offset LSN outside CDC retention window",
                            "db", db.dbName(),
                            "commitLsn", info.commitLsn);
                    allOffsetsOk = false;
                } else {
                    Log.info(log, "sync database",
                            "db", db.dbName(),
                            "tables", db.tables(),
                            "snapshotRows", db.rows(),
                            "snapshotLsn", info.commitLsn != null ? info.commitLsn : "?",
                            "streamingFrom", info.commitLsn != null ? info.commitLsn : "snapshot start");
                }
            }
        }
        if (!allOffsetsOk) {
            return StepResult.fatal(
                    "Offset files not ready. Run snapshot first to generate them, then sync will resume from the snapshot point.");
        }

        TaskManager taskManager = ctx.get("taskManager", TaskManager.class);
        String taskName = ctx.get("taskName", String.class);

        // 3b. Ensure schema history exists for each database. If a DB has an
        // offset but no schema history (e.g. after a dump), run a quick
        // no_data snapshot to capture the table schemas, then restore the
        // dump's LSN so CDC starts from the pre-dump point.
        ensureSchemaHistory(okDbs, cfg, taskName);

        // 4. Create writer and converter shared across all DB engines
        SinkRecordConverter converter = new SinkRecordConverter(targetDs);
        SyncWriter writer = new SyncWriter(converter);
        SyncEngineFactory engineFactory = new SyncEngineFactory(
                config.getSource(), sourceDriver);

        // 5. Sync loop — supports pause/resume/stop without process restart
        int dbCount = okDbs.size();
        ConcurrentHashMap<String, Throwable> engineErrors = new ConcurrentHashMap<>();
        AtomicBoolean shuttingDown = new AtomicBoolean(false);
        boolean stoppedByUser = false;

        outer:
        while (true) {
            ExecutorService executor = Executors.newFixedThreadPool(dbCount);
            ConcurrentHashMap<String, DebeziumEngine<ChangeEvent<String, String>>> engines = new ConcurrentHashMap<>();
            CountDownLatch allDone = new CountDownLatch(dbCount);

            for (SnapshotDbResult db : okDbs) {
                executor.submit(() -> {
                    MDC.put("task", taskName);
                    try {
                        SyncSink sink = new SyncSink(writer, targetDs, "any2tidb_" + db.dbName());

                        DebeziumEngine<ChangeEvent<String, String>> engine = engineFactory.create(
                                db.dbName(), cfg, sink, taskName, error -> {
                                    if (error != null) {
                                        engineErrors.put(db.dbName(), error);
                                        Log.error(log, "sync engine error",
                                                "db", db.dbName(), "error", error.getMessage());
                                    }
                                    allDone.countDown();
                                });
                        engines.put(db.dbName(), engine);

                        engine.run();
                    } catch (Exception e) {
                        engineErrors.put(db.dbName(), e);
                        Log.error(log, "sync engine error", "db", db.dbName(), "error", e.getMessage());
                        allDone.countDown();
                    } finally {
                        MDC.remove("task");
                    }
                });
            }

            // 6. Shutdown hook — graceful stop on SIGTERM
            Thread shutdownHook = new Thread(() -> {
                if (!shuttingDown.compareAndSet(false, true)) return;
                for (var entry : engines.entrySet()) {
                    try { entry.getValue().close(); }
                    catch (Throwable ignored) {}
                }
            }, "sync-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // 7. Wait until all engines complete, stop, or pause is requested
            boolean paused = false;
            while (!allDone.await(2, TimeUnit.SECONDS)) {
                if (taskManager != null && taskName != null
                        && taskManager.isStopRequested(taskName)) {
                    Log.info(log, "stop requested, shutting down engines gracefully");
                    stoppedByUser = true;
                    ctx.put("stopped", true);
                    for (var entry : engines.entrySet()) {
                        try { entry.getValue().close(); }
                        catch (Throwable ignored) {}
                    }
                    break;
                }
                if (taskManager != null && taskName != null
                        && taskManager.isPauseRequested(taskName)) {
                    Log.info(log, "pause requested, pausing engines");
                    paused = true;
                    for (var entry : engines.entrySet()) {
                        try { entry.getValue().close(); }
                        catch (Throwable ignored) {}
                    }
                    break;
                }
            }

            // 8. Cleanup this iteration's resources
            if (!shuttingDown.get()) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (Exception ignored) {}
            }
            // engine.close() (called above) is synchronous and blocks until
            // offsets + schema history are fully flushed. The executor threads
            // return from run() immediately after close(), so shutdown should
            // be near-instant. Generous timeout as safety net only.
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                Log.warn(log, "executor threads did not exit within 30s, forcing shutdown");
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }

            if (!engineErrors.isEmpty()) {
                StringBuilder msg = new StringBuilder("Sync engine(s) failed: ");
                engineErrors.forEach((db, err) ->
                        msg.append(db).append("=").append(err.getMessage()).append("; "));
                return StepResult.fatal(msg.toString());
            }

            if (stoppedByUser) {
                break outer;
            }

            if (paused) {
                // Persist PAUSED status
                try {
                    TaskMeta meta = taskManager.readMeta(taskName);
                    meta.markPaused();
                    taskManager.writeMeta(taskName, meta);
                } catch (Exception e) {
                    Log.warn(log, "failed to write PAUSED status", "error", e.getMessage());
                }

                Log.info(log, "sync paused, waiting for resume");
                // Wait until .pause file is removed
                while (taskManager.isPauseRequested(taskName)) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }

                // Persist RUNNING status on resume
                try {
                    TaskMeta meta = taskManager.readMeta(taskName);
                    meta.markResumed();
                    taskManager.writeMeta(taskName, meta);
                } catch (Exception e) {
                    Log.warn(log, "failed to write RUNNING status on resume", "error", e.getMessage());
                }

                Log.info(log, "resuming sync");
                continue outer;
            }

            break outer; // natural completion
        }

        if (!engineErrors.isEmpty()) {
            StringBuilder msg = new StringBuilder("Sync engine(s) failed: ");
            engineErrors.forEach((db, err) ->
                    msg.append(db).append("=").append(err.getMessage()).append("; "));
            return StepResult.fatal(msg.toString());
        }
        return stoppedByUser
                ? StepResult.ok("Sync stopped by user request.")
                : StepResult.ok("Sync stopped.");
    }

    // ── schema history auto-init ─────────────────────────────────────────────

    /**
     * For databases that have an offset file but no schema history (e.g. after a
     * dump), run a quick Debezium {@code no_data} snapshot to capture
     * table schemas, then restore the original offset LSN so CDC streaming starts
     * from the pre-dump point.
     */
    private void ensureSchemaHistory(List<SnapshotDbResult> okDbs, SyncConfig cfg, String taskName) {
        SyncEngineFactory factory = new SyncEngineFactory(
                config.getSource(), sourceDriver);
        for (SnapshotDbResult db : okDbs) {
            String historyPath = cfg.schemaHistoryPath() + "/" + db.dbName() + ".history";
            if (isValidHistoryFile(historyPath)) continue;

            String offsetPath = cfg.offsetStoragePath() + "/" + db.dbName() + ".offset";
            OffsetInfo dumpOffset = readOffsetInfo(offsetPath, db.dbName());
            String dumpLsn = dumpOffset != null ? dumpOffset.commitLsn : null;

            Log.info(log, "schema history not found, running no_data",
                    "db", db.dbName(),
                    "dumpLsn", dumpLsn != null ? dumpLsn : "?");

            try {
                SyncConfig schemaCfg = cfg.withSnapshotMode("no_data");
                DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> noopConsumer =
                        (records, committer) -> {
                            for (ChangeEvent<String, String> r : records) {
                                committer.markProcessed(r);
                            }
                            committer.markBatchFinished();
                        };
                DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                        db.dbName(), schemaCfg, noopConsumer, taskName, error -> {});
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> engineError = new AtomicReference<>();
                ExecutorService exec = Executors.newSingleThreadExecutor();
                exec.submit(() -> {
                    MDC.put("task", taskName);
                    try { engine.run(); }
                    catch (Throwable t) { engineError.set(t); }
                    finally { done.countDown(); MDC.remove("task"); }
                });
                if (!done.await(5, TimeUnit.MINUTES)) {
                    Log.warn(log, "no_data timeout, closing engine",
                            "db", db.dbName());
                    engine.close();
                    done.await(30, TimeUnit.SECONDS);
                }
                exec.shutdown();
                if (!exec.awaitTermination(30, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                    exec.awaitTermination(5, TimeUnit.SECONDS);
                }

                if (engineError.get() != null) {
                    Log.error(log, "no_data failed", "db", db.dbName(),
                            "error", engineError.get().getMessage());
                    continue;
                }
                Log.info(log, "no_data complete", "db", db.dbName());
            } catch (Exception e) {
                Log.error(log, "no_data failed", "db", db.dbName(),
                        "error", e.getMessage());
                continue;
            }

            // Patch offset — no_data wrote current LSN, restore dump's LSN
            // Use patchOffsetLsn to preserve all other Debezium-written fields
            if (dumpLsn != null) {
                try {
                    patchOffsetLsn(offsetPath, db.dbName(), dumpLsn);
                } catch (Exception e) {
                    Log.warn(log, "failed to patch offset LSN",
                            "db", db.dbName(), "error", e.getMessage());
                }
            }
        }
    }

    /**
     * Debezium 3.x {@code FileSchemaHistory} writes JSON lines (one record per line).
     * A corrupt file (e.g. from hard-killed process) is truncated or not valid JSON.
     */
    private static boolean isValidHistoryFile(String path) {
        File file = new File(path);
        if (!file.exists() || file.length() == 0) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String firstLine = r.readLine();
            if (firstLine == null || firstLine.isBlank()) return false;
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstLine);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── offset file reading ──────────────────────────────────────────────────

    /**
     * Debezium FileOffsetBackingStore serializes a {@code HashMap<byte[], byte[]>}
     * (Debezium 3.x) or {@code HashMap<ByteBuffer, ByteBuffer>} (older versions)
     * using standard Java serialization. Keys are engine-name JSON, values are offset JSON.
     */
    @SuppressWarnings("unchecked")
    private static OffsetInfo readOffsetInfo(String path, String dbName) {
        File file = new File(path);
        if (!file.exists() || file.length() == 0) return null;

        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            Map<?, ?> rawMap = (Map<?, ?>) ois.readObject();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                String keyJson = decodeOffsetEntry(entry.getKey());
                String valueJson = decodeOffsetEntry(entry.getValue());
                if (keyJson == null || valueJson == null) continue;
                if (!keyJson.contains(dbName)) continue;

                JsonNode root = new ObjectMapper().readTree(valueJson);
                String commitLsn = root.has("commit_lsn") ? root.get("commit_lsn").asText() : null;
                // snapshot_completed is only present during snapshot phase.
                // Streaming-mode offsets omit it entirely — absence means snapshot
                // was already complete and the engine was in streaming mode.
                boolean snapshotCompleted = !root.has("snapshot_completed")
                        || root.get("snapshot_completed").asBoolean(false);
                return new OffsetInfo(commitLsn, snapshotCompleted);
            }
        } catch (Exception e) {
            // Corrupt offset file or deserialization error
        }
        return null;
    }

    /** Handle both {@code byte[]} (Debezium 3.x) and {@code ByteBuffer} (older). */
    private static String decodeOffsetEntry(Object entry) {
        if (entry instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (entry instanceof ByteBuffer bb) {
            return StandardCharsets.UTF_8.decode(bb).toString();
        }
        return null;
    }

    private record OffsetInfo(String commitLsn, boolean snapshotCompleted) {}

    // ── LSN CDC window check ─────────────────────────────────────────────────

    /**
     * Verify the offset LSN is still within the CDC retention window.
     * If {@code sys.fn_cdc_map_lsn_to_time} returns NULL, the LSN has been
     * cleaned up and CDC streaming cannot resume from this point.
     */
    private boolean lsnInCdcWindow(String dbName, String commitLsn) {
        if (commitLsn == null || !commitLsn.matches("[0-9A-Fa-f]{8}:[0-9A-Fa-f]{8}:[0-9A-Fa-f]{4}")) {
            Log.warn(log, "unexpected LSN format, skipping window check",
                    "db", dbName, "commitLsn", commitLsn);
            return true; // can't validate — assume OK
        }
        String hex = commitLsn.replace(":", "");
        String url = sourceDriver.buildJdbcUrlTo(config.getSource(), dbName);
        try (Connection c = DriverManager.getConnection(url,
                config.getSource().getUsername(), config.getSource().getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sys.fn_cdc_map_lsn_to_time(CONVERT(BINARY(10), '0x" + hex + "', 1)) AS ts")) {
            return rs.next() && rs.getString("ts") != null;
        } catch (Exception e) {
            Log.warn(log, "LSN window check failed, assuming OK",
                    "db", dbName, "error", e.getMessage());
            return true; // can't verify — assume OK, don't block
        }
    }

    // ── snapshot meta ─────────────────────────────────────────────────────

    private List<SnapshotDbResult> readSnapshotFromDb(StepContext ctx) {
        List<SnapshotDbResult> results = new ArrayList<>();
        try {
            TaskManager tm = ctx.get("taskManager", TaskManager.class);
            String taskName = ctx.get("taskName", String.class);
            if (tm == null || taskName == null) return results;

            TaskMeta meta = tm.readMeta(taskName);
            String fromTask = meta.getFromTask();
            if (fromTask == null) return results;

            List<TaskManager.SnapshotResult> rows = tm.readSnapshotResults(fromTask);
            for (TaskManager.SnapshotResult r : rows) {
                results.add(new SnapshotDbResult(
                        r.dbName(), r.tables(), r.rows(),
                        Instant.now(), r.error()));
            }
            if (!results.isEmpty()) {
                Log.info(log, "read snapshot results from database",
                        "fromTask", fromTask, "databases", results.size());
            }
        } catch (Exception e) {
            Log.warn(log, "failed to read snapshot results from DB, will try file fallback",
                    "error", e.getMessage());
        }
        return results;
    }

    /** File-based fallback for backward compatibility with old snapshot-meta.json. */
    private List<SnapshotDbResult> readSnapshotMetaFile(String path) {
        List<SnapshotDbResult> results = new ArrayList<>();
        File file = new File(path);
        if (!file.exists()) return results;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file);
            if (root == null) return results;
            JsonNode dbs = root.get("databases");
            if (dbs == null) return results;

            var fields = dbs.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String dbName = entry.getKey();
                JsonNode db = entry.getValue();
                if (db == null) continue;
                String error = db.has("error") && !db.get("error").isNull() ? db.get("error").asText() : null;
                results.add(new SnapshotDbResult(
                        dbName,
                        db.path("tables").asInt(0),
                        db.path("rows").asLong(0),
                        Instant.now(),
                        error
                ));
            }
        } catch (Exception e) {
            Log.error(log, "failed to read snapshot meta", "path", path, "error", e.getMessage());
        }
        return results;
    }
}
