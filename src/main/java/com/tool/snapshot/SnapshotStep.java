package com.tool.snapshot;

import com.tool.config.AppConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class SnapshotStep implements MigrationStep {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStep.class);
    private static final long ENGINE_TIMEOUT_MINUTES = 30;

    private final AppConfig config;
    private final DataSource targetDs;

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
        log.info("database discovery  count={}", dbNames.size());

        if (databases != null && !databases.isEmpty()) {
            dbNames = dbNames.stream().filter(databases::contains).toList();
        }
        if (dbNames.isEmpty()) {
            return StepResult.ok("no databases to snapshot");
        }

        CdcPreChecker cdcChecker = new CdcPreChecker(config.getSource());
        List<SnapshotDbResult> dbResults = new ArrayList<>();
        long totalRows = 0L;

        for (String dbName : dbNames) {
            SnapshotDbResult dbResult = snapshotDatabase(
                    dbName, tables, cdcChecker, snapshotConfig,
                    enableCdc != null && enableCdc);
            dbResults.add(dbResult);
            if (!dbResult.isError()) {
                totalRows += dbResult.rows();
            }
            printDbResult(dbName, dbResult);
        }

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
                return new SnapshotDbResult(dbName, null, null, 0, 0L,
                        Instant.now(), cdcResult.errorMessage());
            }

            TiDBBatchWriter batchWriter = new TiDBBatchWriter(
                    targetDs, new SinkRecordConverter(), snapshotConfig.batchInsertSize());
            SnapshotSink sink = new SnapshotSink(batchWriter);

            DebeziumEngineFactory factory = new DebeziumEngineFactory(config.getSource());
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> engineError = new AtomicReference<>();

            DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                    dbName, snapshotConfig, tables, sink, done::countDown);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    try {
                        engine.run();
                    } catch (Throwable t) {
                        engineError.set(t);
                        done.countDown();
                    }
                });
                boolean completed = done.await(ENGINE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (!completed) {
                    return new SnapshotDbResult(dbName, sink.getCommitLsn(), sink.getChangeLsn(),
                            tableList.size(), batchWriter.getTotalRows(), Instant.now(),
                            "Snapshot timed out after " + ENGINE_TIMEOUT_MINUTES + " minutes");
                }
                if (engineError.get() != null) {
                    return new SnapshotDbResult(dbName, null, null, 0, 0L,
                            Instant.now(), "Engine error: " + engineError.get().getMessage());
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.MINUTES);
                batchWriter.flushAll();
            }

            return new SnapshotDbResult(dbName, sink.getCommitLsn(), sink.getChangeLsn(),
                    tableList.size(), batchWriter.getTotalRows(), Instant.now(), null);
        } catch (Exception e) {
            log.error("database snapshot error  database={}  error=\"{}\"", dbName, e.getMessage());
            return new SnapshotDbResult(dbName, null, null, 0, 0L,
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
                json.append("      \"commitLsn\": ").append(db.commitLsn() != null ? "\"" + db.commitLsn() + "\"" : "null").append(",\n");
                json.append("      \"changeLsn\": ").append(db.changeLsn() != null ? "\"" + db.changeLsn() + "\"" : "null").append(",\n");
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
            log.info("snapshot-meta.json written");
        } catch (Exception e) {
            log.warn("failed to write snapshot-meta.json  error=\"{}\"", e.getMessage());
        }
    }

    private void printDbResult(String dbName, SnapshotDbResult r) {
        if (r.isError()) {
            log.error("database snapshot failed  database={}  error=\"{}\"", dbName, r.error());
        } else {
            log.info("database snapshot done  database={}  tables={}  rows={}  lsn={}",
                    dbName, r.tables(), r.rows(),
                    r.commitLsn() != null ? r.commitLsn() : "(none)");
        }
    }
}
