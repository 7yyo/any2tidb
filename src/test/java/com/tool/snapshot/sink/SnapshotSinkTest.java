package com.tool.snapshot.sink;

import io.debezium.engine.ChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SnapshotSinkTest {

    private TiDBBatchWriter batchWriter;
    private SnapshotSink sink;

    @BeforeEach
    void setUp() {
        batchWriter = mock(TiDBBatchWriter.class);
        sink = new SnapshotSink(batchWriter);
    }

    private static ChangeEvent<String, String> event(String key, String value) {
        return new ChangeEvent<>() {
            @Override public String key() { return key; }
            @Override public String value() { return value; }
            @Override public String destination() { return null; }
            @Override public Integer partition() { return null; }
        };
    }

    private String makeSnapshotJson(String dbName, String table, String afterJson) {
        return """
        {
            "payload": {
                "before": null,
                "after": %s,
                "source": {
                    "db": "%s",
                    "schema": "dbo",
                    "table": "%s",
                    "commit_lsn": "00000025:00000338:0003",
                    "change_lsn": "00000025:00000338:0002",
                    "snapshot": "true"
                },
                "op": "r"
            }
        }
        """.formatted(afterJson, dbName, table);
    }

    @Test
    void snapshotRecord_withAfterData_isRoutedToWriter() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1, \"name\": \"Alice\"}");

        sink.accept(List.of(event("key", json)));

        verify(batchWriter).accumulate(eq("testdb"), eq("users"), any(Map.class));
    }

    @Test
    void snapshotRecord_nullAfter_isSkipped() {
        String json = makeSnapshotJson("testdb", "users", "null");

        sink.accept(List.of(event("key", json)));

        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }

    @Test
    void nonSnapshotRecord_isSkipped() {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "false"},
                "op": "c"
            }
        }
        """;

        sink.accept(List.of(event("key", json)));

        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }

    @Test
    void lsnExtracted_fromSnapshotRecord() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1}");

        sink.accept(List.of(event("key", json)));

        assertEquals("00000025:00000338:0003", sink.getCommitLsn());
        assertEquals("00000025:00000338:0002", sink.getChangeLsn());
    }

    @Test
    void reset_clearsLsn() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1}");

        sink.accept(List.of(event("key", json)));
        sink.reset();

        assertNull(sink.getCommitLsn());
        assertNull(sink.getChangeLsn());
    }

    @Test
    void recordConversionError_loggedAndSkipped() {
        sink.accept(List.of(event("key", "not valid json")));

        assertDoesNotThrow(() -> sink.accept(List.of(event("key", "not valid json"))));
        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }
}
