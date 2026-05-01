package com.tool.snapshot;

import com.tool.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.logging.Log;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.snapshot.cdc.CdcPreChecker;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class SnapshotStep implements MigrationStep {

    private static final long ENGINE_TIMEOUT_MINUTES = 30;

    private final AppConfig config;
    private final DataSource targetDs;
    private static final Logger log = LoggerFactory.getLogger(SnapshotStep.class);

    public SnapshotStep(AppConfig config, DataSource targetDs) {
        this.config = config;
        this.targetDs = targetDs;
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
        Boolean enableCdc = ctx.get("enableCdc", Boolean.class);

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
                config.getSource().jdbcUrl(),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = discoverDatabases(master);
        } catch (Exception e) {
            return StepResult.fatal("Cannot connect to source database: " + e.getMessage());
        }
        Log.info(log, "database discovery", "count", dbNames.size(), "databases", dbNames);

        if (databases != null && !databases.isEmpty()) {
            dbNames = dbNames.stream().filter(databases::contains).toList();
        }
        if (dbNames.isEmpty()) {
            return StepResult.ok("no databases to snapshot");
        }

        CdcPreChecker cdcChecker = new CdcPreChecker(config.getSource());
        List<SnapshotDbResult> dbResults = new ArrayList<>();
        long totalRows = 0L;
        long startMs = System.currentTimeMillis();

        for (String dbName : dbNames) {
            long dbStartMs = System.currentTimeMillis();
            SnapshotDbResult dbResult = snapshotDatabase(
                    dbName, tables, cdcChecker, snapshotConfig,
                    enableCdc != null && enableCdc);
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
        writeSnapshotMeta(dbResults, totalRows);

        if (SnapshotDbResult.shouldBlockPipeline(dbResults)) {
            return StepResult.fatal("snapshot completed with errors");
        }
        return StepResult.ok("snapshot complete, databases=" + dbResults.size() + " rows=" + totalRows);
    }

    private SnapshotDbResult snapshotDatabase(String dbName, List<String> tables,
                                               CdcPreChecker cdcChecker,
                                               SnapshotConfig snapshotConfig,
                                               boolean autoEnable) {
        try (Connection conn = DriverManager.getConnection(
                config.getSource().jdbcUrlTo(dbName),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {

            List<String[]> tableList = discoverTables(conn, tables);
            CdcPreChecker.CdcCheckResult cdcResult = cdcChecker.check(conn, dbName, tableList, autoEnable);
            if (cdcResult.hasError()) {
                return new SnapshotDbResult(dbName, 0, 0L,
                        Instant.now(), cdcResult.errorMessage());
            }

            TiDBBatchWriter batchWriter = new TiDBBatchWriter(
                    targetDs, new SinkRecordConverter(targetDs), snapshotConfig.batchInsertSize(),
                    snapshotConfig.snapshotMaxThreads());
            Map<String, Long> estimates = estimateRowCounts(conn, tableList);
            batchWriter.setTableEstimates(estimates);
            Log.info(log, "row estimates", "database", dbName, "estimates", estimates.toString());
            SnapshotSink sink = new SnapshotSink(batchWriter);

            // Delete previous offset so snapshot always runs fresh
            Path offsetFile = Path.of(snapshotConfig.offsetStoragePath(), dbName + ".offset");
            try { Files.deleteIfExists(offsetFile); } catch (Exception ignored) {}
            Path historyFile = Path.of(snapshotConfig.schemaHistoryPath(), dbName + ".history");
            try { Files.deleteIfExists(historyFile); } catch (Exception ignored) {}

            DebeziumEngineFactory factory = new DebeziumEngineFactory(config.getSource());
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

    private List<String> discoverDatabases(Connection conn) throws Exception {
        List<String> dbs = new ArrayList<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT name FROM sys.databases WHERE name NOT IN ('master','tempdb','model','msdb') AND state_desc = 'ONLINE'")) {
            while (rs.next()) dbs.add(rs.getString("name"));
        }
        return dbs;
    }

    private List<String[]> discoverTables(Connection conn, List<String> tableFilter) throws Exception {
        List<String[]> tables = new ArrayList<>();
        String sql = "SELECT s.name, t.name FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE t.type = 'U' AND t.is_ms_shipped = 0 AND s.name NOT IN ('sys', 'INFORMATION_SCHEMA', 'cdc')";
        if (tableFilter != null && !tableFilter.isEmpty()) {
            String inClause = tableFilter.stream().map(t -> "?").collect(Collectors.joining(","));
            sql += " AND t.name IN (" + inClause + ")";
        }
        sql += " ORDER BY s.name, t.name";
        try (var ps = conn.prepareStatement(sql)) {
            if (tableFilter != null) {
                for (int i = 0; i < tableFilter.size(); i++) {
                    ps.setString(i + 1, tableFilter.get(i));
                }
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        return tables;
    }

    /**
     * Fast approximate row counts via sys.dm_db_partition_stats.
     * Returns map of {@code "table" → estimatedRows} (table name only, no schema).
     */
    private Map<String, Long> estimateRowCounts(Connection conn, List<String[]> tables) {
        Map<String, Long> estimates = new HashMap<>();
        if (tables.isEmpty()) return estimates;
        // Group by schema, run one query per schema
        var schemaGroups = new HashMap<String, List<String>>();
        for (String[] t : tables) {
            schemaGroups.computeIfAbsent(t[0], k -> new ArrayList<>()).add(t[1]);
        }
        for (var entry : schemaGroups.entrySet()) {
            String schema = entry.getKey();
            List<String> schemaTables = entry.getValue();
            String inClause = schemaTables.stream().map(t -> "?").collect(Collectors.joining(","));
            String sql = "SELECT t.name, SUM(p.row_count) FROM sys.tables t " +
                         "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                         "JOIN sys.dm_db_partition_stats p ON t.object_id = p.object_id " +
                         "WHERE p.index_id IN (0,1) AND s.name=? AND t.name IN (" + inClause + ") " +
                         "GROUP BY t.name";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                for (int i = 0; i < schemaTables.size(); i++) {
                    ps.setString(i + 2, schemaTables.get(i));
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) estimates.put(rs.getString(1), rs.getLong(2));
                }
            } catch (Exception e) {
                Log.warn(log, "row estimate query failed", "schema", schema, "error", e.getMessage());
            }
        }
        return estimates;
    }

    private void writeSnapshotMeta(List<SnapshotDbResult> dbResults, long totalRows) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"tool\": \"any2tidb\",\n");
            json.append("  \"command\": \"snapshot\",\n");
            json.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
            json.append("  \"databases\": {\n");
            for (int i = 0; i < dbResults.size(); i++) {
                SnapshotDbResult db = dbResults.get(i);
                json.append("    \"").append(db.dbName()).append("\": {\n");
                json.append("      \"tables\": ").append(db.tables()).append(",\n");
                json.append("      \"rows\": ").append(db.rows()).append("\n");
                json.append("    }");
                if (i < dbResults.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  },\n");
            json.append("  \"totalRows\": ").append(totalRows).append(",\n");
            int totalTables = dbResults.stream().mapToInt(SnapshotDbResult::tables).sum();
            json.append("  \"totalTables\": ").append(totalTables).append("\n");
            json.append("}\n");
            Files.writeString(Path.of("snapshot-meta.json"), json);
            Log.info(log, "snapshot-meta.json written");
        } catch (Exception e) {
            Log.warn(log, "failed to write snapshot-meta.json", "error", e.getMessage());
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
