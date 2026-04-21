package com.tool.snapshot.sink;

import io.debezium.engine.ChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSink.class);

    private final TiDBBatchWriter batchWriter;
    private final SnapshotJsonParser parser = new SnapshotJsonParser();
    private String commitLsn;
    private String changeLsn;

    public SnapshotSink(TiDBBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public void accept(List<ChangeEvent<String, String>> events) {
        for (ChangeEvent<String, String> event : events) {
            try {
                String json = event.value();
                if (json == null) continue;
                SnapshotJsonParser.ParsedRecord record = parser.parse(json);
                if (!record.isSnapshot()) continue;
                if (record.after() == null) continue;
                batchWriter.accumulate(record.dbName(), record.table(), record.after());
                trackLsn(record);
            } catch (Exception e) {
                log.error("[\"record failed\"] [error=\"{}\"]", e.getMessage());
            }
        }
    }

    private void trackLsn(SnapshotJsonParser.ParsedRecord record) {
        if (record.commitLsn() != null) commitLsn = record.commitLsn();
        if (record.changeLsn() != null) changeLsn = record.changeLsn();
    }

    public String getCommitLsn() { return commitLsn; }
    public String getChangeLsn() { return changeLsn; }

    public void reset() {
        commitLsn = null;
        changeLsn = null;
    }
}
