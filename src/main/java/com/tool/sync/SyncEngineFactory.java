package com.tool.sync;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.source.SourceDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;

import java.util.Properties;
import java.util.function.Consumer;

public class SyncEngineFactory {

    private final AppConfig.DbConfig source;
    private final SourceDriver driver;
    private static final Logger log = LoggerFactory.getLogger(SyncEngineFactory.class);

    public SyncEngineFactory(AppConfig.DbConfig source, SourceDriver driver) {
        this.source = source;
        this.driver = driver;
    }

    public DebeziumEngine<ChangeEvent<String, String>> create(
            String dbName,
            SyncConfig syncConfig,
            DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> changeConsumer,
            String taskName,
            Consumer<Throwable> onComplete) {

        Properties props = new Properties();
        props.setProperty("name", "any2tidb-snapshot-" + dbName);
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
                syncConfig.schemaHistoryPath() + "/" + dbName + ".history");
        props.setProperty("offset.storage.file.filename",
                syncConfig.offsetStoragePath() + "/" + dbName + ".offset");
        // Default "initial" reads existing offset and skips snapshot since it was already completed.
        // no_data runs a quick schema capture without data transfer.
        if (syncConfig.snapshotMode() != null) {
            props.setProperty("snapshot.mode", syncConfig.snapshotMode());
        }
        props.setProperty("table.include.list", ".*\\..*");
        props.setProperty("max.queue.size", "16384");
        props.setProperty("poll.interval.ms", String.valueOf(syncConfig.pollIntervalMs()));
        props.setProperty("offset.flush.interval.ms", "10000");
        props.setProperty("decimal.handling.mode", "string");
        // Do NOT set record.processing.threads or record.processing.order —
        // keep default ORDERED serial for transactional consistency

        return DebeziumEngine.create(Json.class)
                .using(props)
                .notifying((records, committer) -> {
                    MDC.put("task", taskName);
                    try {
                        changeConsumer.handleBatch(records, committer);
                    } finally {
                        MDC.remove("task");
                    }
                })
                .using((DebeziumEngine.CompletionCallback) (success, message, error) -> {
                    MDC.put("task", taskName);
                    try {
                        if (error != null) {
                            Log.error(log, "sync engine failed", "db", dbName, "error", error.getMessage());
                        }
                        if (onComplete != null) onComplete.accept(error);
                    } finally {
                        MDC.remove("task");
                    }
                })
                .build();
    }
}
