package com.tool.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.snapshot.model.SnapshotDbResult;
import com.tool.snapshot.sink.SinkRecordConverter;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import javax.sql.DataSource;
import static com.tool.source.sqlserver.SqlServerCdcUtils.patchOffsetLsn;

import java.io.File;
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
    private static final Logger log = LoggerFactory.getLogger(SyncStep.class);

    public SyncStep(AppConfig config, DataSource targetDs) {
        this.config = config;
        this.targetDs = targetDs;
    }

    @Override
    public String name() { return "Sync"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        SyncConfig syncConfig = ctx.get("syncConfig", SyncConfig.class);
        final SyncConfig cfg = syncConfig != null ? syncConfig : SyncConfig.defaults();

        // 1. Read snapshot results — prefer ctx, fallback to meta file
        List<SnapshotDbResult> dbResults = ctx.get("snapshotSummaries", List.class);
        if (dbResults == null || dbResults.isEmpty()) {
            dbResults = readSnapshotMeta(cfg.metaFile());
        }
        if (dbResults.isEmpty()) {
            return StepResult.fatal("No snapshot results found. Run snapshot first, or provide --meta-file.");
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
                        "database", db.dbName(),
                        "offsetPath", offsetPath,
                        "found", info != null,
                        "snapshotCompleted", info != null ? info.snapshotCompleted : false);
                allOffsetsOk = false;
            } else {
                Log.info(log, "sync database",
                        "database", db.dbName(),
                        "tables", db.tables(),
                        "snapshotRows", db.rows(),
                        "snapshotLsn", info.commitLsn != null ? info.commitLsn : "?",
                        "streamingFrom", info.commitLsn != null ? info.commitLsn : "snapshot start");
            }
        }
        if (!allOffsetsOk) {
            return StepResult.fatal(
                    "Offset files not ready. Run snapshot first to generate them, then sync will resume from the snapshot point.");
        }

        // 3b. Ensure schema history exists for each database. If a DB has an
        // offset but no schema history (e.g. after a NOLOCK dump), run a quick
        // schema_only snapshot to capture the table schemas, then restore the
        // dump's LSN so CDC starts from the pre-dump point.
        ensureSchemaHistory(okDbs, cfg);

        // 4. Create writer and converter shared across all DB engines
        SinkRecordConverter converter = new SinkRecordConverter(targetDs);
        SyncWriter writer = new SyncWriter(converter);
        SyncEngineFactory engineFactory = new SyncEngineFactory(config.getSource());

        // 5. Launch one engine per database concurrently
        int dbCount = okDbs.size();
        ExecutorService executor = Executors.newFixedThreadPool(dbCount);
        ConcurrentHashMap<String, DebeziumEngine<ChangeEvent<String, String>>> engines = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Throwable> engineErrors = new ConcurrentHashMap<>();
        CountDownLatch allDone = new CountDownLatch(dbCount);
        AtomicBoolean shuttingDown = new AtomicBoolean(false);

        for (SnapshotDbResult db : okDbs) {
            executor.submit(() -> {
                try {
                    SyncSink sink = new SyncSink(writer, targetDs, "any2tidb_" + db.dbName());

                    DebeziumEngine<ChangeEvent<String, String>> engine = engineFactory.create(
                            db.dbName(), cfg, sink, error -> {
                                if (error != null) {
                                    engineErrors.put(db.dbName(), error);
                                    Log.error(log, "sync engine error",
                                            "database", db.dbName(), "error", error.getMessage());
                                }
                                allDone.countDown();
                            });
                    engines.put(db.dbName(), engine);

                    engine.run();
                } catch (Exception e) {
                    engineErrors.put(db.dbName(), e);
                    Log.error(log, "sync engine error", "database", db.dbName(), "error", e.getMessage());
                    allDone.countDown();
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

        // 7. Wait until all engines complete (or shutdown triggered)
        try {
            allDone.await();
        } catch (InterruptedException ignored) {}

        // 8. Cleanup
        if (!shuttingDown.get()) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (!engineErrors.isEmpty()) {
            StringBuilder msg = new StringBuilder("Sync engine(s) failed: ");
            engineErrors.forEach((db, err) ->
                    msg.append(db).append("=").append(err.getMessage()).append("; "));
            return StepResult.fatal(msg.toString());
        }
        return StepResult.ok("Sync stopped.");
    }

    // ── schema history auto-init ─────────────────────────────────────────────

    /**
     * For databases that have an offset file but no schema history (e.g. after a
     * NOLOCK dump), run a quick Debezium {@code schema_only} snapshot to capture
     * table schemas, then restore the original offset LSN so CDC streaming starts
     * from the pre-dump point.
     */
    private void ensureSchemaHistory(List<SnapshotDbResult> okDbs, SyncConfig cfg) {
        SyncEngineFactory factory = new SyncEngineFactory(config.getSource());
        for (SnapshotDbResult db : okDbs) {
            String historyPath = cfg.schemaHistoryPath() + "/" + db.dbName() + ".history";
            if (new File(historyPath).exists()) continue;

            String offsetPath = cfg.offsetStoragePath() + "/" + db.dbName() + ".offset";
            OffsetInfo dumpOffset = readOffsetInfo(offsetPath, db.dbName());
            String dumpLsn = dumpOffset != null ? dumpOffset.commitLsn : null;

            Log.info(log, "schema history not found, running schema_only",
                    "database", db.dbName(),
                    "dumpLsn", dumpLsn != null ? dumpLsn : "?");

            try {
                SyncConfig schemaCfg = cfg.withSnapshotMode("schema_only");
                DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> noopConsumer =
                        (records, committer) -> {
                            for (ChangeEvent<String, String> r : records) {
                                committer.markProcessed(r);
                            }
                            committer.markBatchFinished();
                        };
                DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                        db.dbName(), schemaCfg, noopConsumer, error -> {});
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Throwable> engineError = new AtomicReference<>();
                ExecutorService exec = Executors.newSingleThreadExecutor();
                exec.submit(() -> {
                    try { engine.run(); }
                    catch (Throwable t) { engineError.set(t); }
                    finally { done.countDown(); }
                });
                done.await();
                exec.shutdownNow();
                exec.awaitTermination(5, TimeUnit.SECONDS);

                if (engineError.get() != null) {
                    Log.error(log, "schema_only failed", "database", db.dbName(),
                            "error", engineError.get().getMessage());
                    continue;
                }
                Log.info(log, "schema_only complete", "database", db.dbName());
            } catch (Exception e) {
                Log.error(log, "schema_only failed", "database", db.dbName(),
                        "error", e.getMessage());
                continue;
            }

            // Patch offset — schema_only wrote current LSN, restore dump's LSN
            // Use patchOffsetLsn to preserve all other Debezium-written fields
            if (dumpLsn != null) {
                try {
                    patchOffsetLsn(offsetPath, db.dbName(), dumpLsn);
                } catch (Exception e) {
                    Log.warn(log, "failed to patch offset LSN",
                            "database", db.dbName(), "error", e.getMessage());
                }
            }
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
                boolean snapshotCompleted = root.has("snapshot_completed")
                        && root.get("snapshot_completed").asBoolean(false);
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

    // ── snapshot meta fallback ───────────────────────────────────────────────

    private List<SnapshotDbResult> readSnapshotMeta(String path) {
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
