package com.tool.snapshot.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.logging.Log;
import io.debezium.engine.ChangeEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SnapshotSink {

    private final TiDBBatchWriter batchWriter;
    private final SnapshotJsonParser parser = new SnapshotJsonParser();
    private static final Logger log = LoggerFactory.getLogger(SnapshotSink.class);
    private final Map<String, Long> tableCounts = new HashMap<>();
    private volatile boolean snapshotComplete;

    public SnapshotSink(TiDBBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public void accept(List<ChangeEvent<String, String>> events) {
        for (ChangeEvent<String, String> event : events) {
            SnapshotJsonParser.ParsedRecord record = null;
            try {
                String json = event.value();
                if (json == null) {
                    Log.debug(log, "debezium event skipped", "reason", "null value");
                    continue;
                }
                record = parser.parse(json);

                // "last" = overall snapshot done; "last_in_data_collection" = per-table done
                // These events carry the last row data — accumulate first, then signal completion
                boolean isLastOverall = "last".equals(record.snapshot());
                boolean isLastInTable = "last_in_data_collection".equals(record.snapshot());

                if (!record.isSnapshot()) {
                    Log.debug(log, "debezium event skipped",
                            "reason", "non-snapshot",
                            "table", fullName(record),
                            "snapshot", record.snapshot(),
                            "op", record.op());
                    continue;
                }
                if (record.after() == null) {
                    Log.debug(log, "debezium event skipped",
                            "reason", "null after",
                            "table", fullName(record),
                            "snapshot", record.snapshot());
                    // Even without data, still signal completion
                    if (isLastInTable || isLastOverall) {
                        batchWriter.tableComplete(record.table(), record.dbName());
                    }
                    if (isLastOverall) {
                        snapshotComplete = true;
                    }
                    continue;
                }
                long n = tableCounts.merge(record.table(), 1L, Long::sum);
                Log.debug(log, "debezium record",
                        "table", fullName(record),
                        "n", n,
                        "keys", keysPreview(record));
                batchWriter.accumulate(record.dbName(), record.table(), record.after());

                if (isLastInTable || isLastOverall) {
                    batchWriter.tableComplete(record.table(), record.dbName());
                }
                if (isLastOverall) {
                    snapshotComplete = true;
                }
            } catch (Exception e) {
                String table = record != null ? fullName(record) : "?";
                Log.warn(log, "record failed", "table", table, "error", e.getMessage());
            }
        }
    }

    public boolean isSnapshotComplete() { return snapshotComplete; }

    private static String fullName(SnapshotJsonParser.ParsedRecord r) {
        return (r.dbName() != null ? r.dbName() : "?") + "." + (r.table() != null ? r.table() : "?");
    }

    private static String keysPreview(SnapshotJsonParser.ParsedRecord r) {
        if (r.after() == null) return "null";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (var e : r.after().entrySet()) {
            if (shown >= 3) { sb.append("..."); break; }
            if (shown > 0 && shown < 3) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
            shown++;
        }
        return sb.toString();
    }


    public void logTableCounts() {
        if (tableCounts.isEmpty()) return;
        Log.debug(log, "debezium records per table", "counts", tableCounts.toString());
    }

}
