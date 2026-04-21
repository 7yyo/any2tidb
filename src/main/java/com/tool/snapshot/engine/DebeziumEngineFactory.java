package com.tool.snapshot.engine;

import com.tool.config.AppConfig;
import com.tool.snapshot.SnapshotConfig;
import com.tool.snapshot.sink.SnapshotSink;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

public class DebeziumEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineFactory.class);

    private final AppConfig.DbConfig source;

    public DebeziumEngineFactory(AppConfig.DbConfig source) {
        this.source = source;
    }

    public DebeziumEngine<ChangeEvent<String, String>> create(
            String dbName,
            SnapshotConfig snapshotConfig,
            List<String> tableFilter,
            SnapshotSink sink,
            Runnable onComplete) {

        Properties props = new Properties();
        props.setProperty("name", "any2tidb-snapshot-" + dbName);
        props.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
        props.setProperty("database.hostname", source.getHost());
        props.setProperty("database.port", String.valueOf(source.getPort()));
        props.setProperty("database.user", source.getUsername());
        props.setProperty("database.password", source.getPassword());
        props.setProperty("database.dbname", dbName);
        props.setProperty("database.server.name", "any2tidb_" + dbName);
        props.setProperty("database.history.file.filename",
                snapshotConfig.schemaHistoryPath() + "/" + dbName + ".history");
        props.setProperty("offset.storage.file.filename",
                snapshotConfig.offsetStoragePath() + "/" + dbName + ".offset");
        props.setProperty("snapshot.mode", snapshotConfig.snapshotMode());
        props.setProperty("snapshot.fetch.size", String.valueOf(snapshotConfig.snapshotFetchSize()));
        props.setProperty("max.queue.size", String.valueOf(snapshotConfig.maxQueueSize()));
        props.setProperty("poll.interval.ms", String.valueOf(snapshotConfig.pollIntervalMs()));
        props.setProperty("offset.flush.interval.ms", String.valueOf(snapshotConfig.offsetCommitIntervalMs()));
        props.setProperty("snapshot.max.threads", String.valueOf(snapshotConfig.snapshotMaxThreads()));
        props.setProperty("table.include.list", snapshotConfig.buildTableIncludeList(tableFilter));

        log.info("[\"creating debezium engine\"] [database={}] [snapshotMode={}] [tables={}]",
                dbName, snapshotConfig.snapshotMode(), snapshotConfig.buildTableIncludeList(tableFilter));

        return DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(event -> sink.accept(List.of(event)))
                .using((DebeziumEngine.CompletionCallback) (success, message, error) -> {
                    if (error != null) {
                        log.error("[\"engine failed\"] [database={}] [error=\"{}\"]", dbName, error.getMessage());
                    } else {
                        log.info("[\"engine completed\"] [database={}] [message=\"{}\"]", dbName, message);
                    }
                    if (onComplete != null) onComplete.run();
                })
                .build();
    }
}
