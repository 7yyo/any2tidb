package com.tool.snapshot.engine;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.snapshot.SnapshotConfig;
import com.tool.snapshot.sink.SnapshotSink;
import com.tool.source.SourceDriver;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Properties;

public class DebeziumEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineFactory.class);

    private final AppConfig.DbConfig source;
    private final SourceDriver driver;

    public DebeziumEngineFactory(AppConfig.DbConfig source, SourceDriver driver) {
        this.source = source;
        this.driver = driver;
    }

    public DebeziumEngine<ChangeEvent<String, String>> create(
            String dbName,
            SnapshotConfig snapshotConfig,
            List<String[]> tableFilter,
            SnapshotSink sink,
            String taskName,
            Runnable onComplete) {

        Properties props = new Properties();
        props.setProperty("name", driver.type() + "2tidb-" + dbName);
        props.setProperty("connector.class", driver.debeziumConnectorClass());
        props.setProperty("database.hostname", source.getHost());
        props.setProperty("database.port", String.valueOf(source.getPort()));
        props.setProperty("database.user", source.getUsername());
        props.setProperty("database.password", source.getPassword());
        props.setProperty(driver.debeziumDatabaseProperty(), dbName);
        props.setProperty("topic.prefix", "any2tidb_" + dbName);
        driver.configureDebeziumProperties(props);
        props.setProperty("schema.history.internal",
                "io.debezium.storage.file.history.FileSchemaHistory");
        props.setProperty("schema.history.internal.file.filename",
                snapshotConfig.schemaHistoryPath() + "/" + dbName + ".history");
        props.setProperty("offset.storage.file.filename",
                snapshotConfig.offsetStoragePath() + "/" + dbName + ".offset");
        props.setProperty("snapshot.mode", snapshotConfig.snapshotMode());
        props.setProperty("snapshot.fetch.size", String.valueOf(snapshotConfig.snapshotFetchSize()));
        props.setProperty("max.queue.size", String.valueOf(snapshotConfig.maxQueueSize()));
        props.setProperty("poll.interval.ms", String.valueOf(snapshotConfig.pollIntervalMs()));
        props.setProperty("offset.flush.interval.ms", String.valueOf(snapshotConfig.offsetCommitIntervalMs()));
        props.setProperty("snapshot.max.threads", String.valueOf(snapshotConfig.snapshotMaxThreads()));
        props.setProperty("decimal.handling.mode", "string");
        int maxBatchSize = Math.min(snapshotConfig.snapshotFetchSize(), snapshotConfig.maxQueueSize() / 2);
        props.setProperty("max.batch.size", String.valueOf(maxBatchSize));
        props.setProperty("table.include.list", snapshotConfig.buildTableIncludeList(tableFilter));

        // ORDERED single-threaded: guarantees "snapshot=last" is processed after
        // all data events, so engine.run() return means everything is complete.
        // No need for idle-timeout polling or manual engine.close().

        Log.info(log, "creating debezium engine",
                "database", dbName,
                "snapshotMode", snapshotConfig.snapshotMode(),
                "tables", tableFilter.size());

        return DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(event -> {
                    MDC.put("task", taskName);
                    try {
                        sink.accept(List.of(event));
                    } finally {
                        MDC.remove("task");
                    }
                })
                .using((DebeziumEngine.CompletionCallback) (success, message, error) -> {
                    MDC.put("task", taskName);
                    try {
                        if (error != null) {
                            Log.error(log, "engine failed", "database", dbName, "error", error.getMessage());
                        }
                        if (onComplete != null) onComplete.run();
                    } finally {
                        MDC.remove("task");
                    }
                })
                .build();
    }
}
