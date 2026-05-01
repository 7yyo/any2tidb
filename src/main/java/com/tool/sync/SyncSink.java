package com.tool.sync;

import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.snapshot.sink.SnapshotJsonParser;
import com.tool.snapshot.sink.SnapshotJsonParser.ParsedRecord;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SyncSink implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {

    private final SyncWriter writer;
    private final DataSource dataSource;
    private final SnapshotJsonParser parser;
    private static final Logger log = LoggerFactory.getLogger(SyncSink.class);
    private final String engineName;

    public SyncSink(SyncWriter writer, DataSource dataSource, String engineName) {
        this.writer = writer;
        this.dataSource = dataSource;
        this.parser = new SnapshotJsonParser();
        this.engineName = engineName;
    }

    @Override
    public void handleBatch(
            List<ChangeEvent<String, String>> records,
            DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer
    ) throws InterruptedException {
        ChangeEvent<String, String> currentEvent = null;
        try (Connection conn = dataSource.getConnection()) {
            for (ChangeEvent<String, String> event : records) {
                currentEvent = event;

                // ── Schema change event (engine-level topic, skip) ──
                if (engineName.equals(event.destination())) {
                    committer.markProcessed(event);
                    continue;
                }

                // ── Data change event ──
                String valueJson = event.value();
                if (valueJson == null) {
                    committer.markProcessed(event);
                    continue;
                }

                ParsedRecord record = parser.parse(valueJson);
                String op = record.op();

                if (op == null || "r".equals(op)) {
                    committer.markProcessed(event);
                    continue;
                }

                Map<String, Object> pkMap = parseKey(event.key());

                switch (op) {
                    case "c" -> {
                        Map<String, Object> after = record.after();
                        if (after != null && !after.isEmpty()) {
                            writer.insert(conn, record.dbName(), record.table(),
                                    new ArrayList<>(new LinkedHashMap<>(after).keySet()), after);
                        }
                    }
                    case "u" -> {
                        Map<String, Object> after = record.after();
                        if (after != null) {
                            writer.update(conn, record.dbName(), record.table(), pkMap, after);
                        }
                    }
                    case "d" -> {
                        Map<String, Object> before = record.before();
                        if (before != null) {
                            writer.delete(conn, record.dbName(), record.table(), pkMap, before);
                        }
                    }
                }

                committer.markProcessed(event);
            }
            committer.markBatchFinished();
        } catch (Exception e) {
            Log.error(log, "sync record failed, stopping engine",
                    "table", currentEvent != null ? currentEvent.destination() : "?",
                    "error", e.getMessage());
            throw new RuntimeException("Sync halted on error, restart will resume from breakpoint", e);
        }
    }

    private Map<String, Object> parseKey(String keyJson) {
        try {
            return parser.parseKey(keyJson);
        } catch (Exception e) {
            return null;
        }
    }
}
