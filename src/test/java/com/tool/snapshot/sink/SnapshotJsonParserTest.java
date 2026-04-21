package com.tool.snapshot.sink;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotJsonParserTest {

    private final SnapshotJsonParser parser = new SnapshotJsonParser();

    @Test
    void parse_snapshotRecord_extractsAllFields() throws Exception {
        String json = """
        {
            "schema": {},
            "payload": {
                "before": null,
                "after": {"id": 1, "name": "Alice", "age": 30},
                "source": {
                    "version": "3.5.0.Final",
                    "connector": "sqlserver",
                    "name": "any2tidb_testdb",
                    "db": "testdb",
                    "schema": "dbo",
                    "table": "users",
                    "commit_lsn": "00000025:00000338:0003",
                    "change_lsn": "00000025:00000338:0002",
                    "snapshot": "true"
                },
                "op": "r",
                "ts_ms": 1714000000000
            }
        }
        """;

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertEquals("testdb", r.dbName());
        assertEquals("dbo", r.schema());
        assertEquals("users", r.table());
        assertNotNull(r.after());
        assertEquals(1, ((Number) r.after().get("id")).intValue());
        assertEquals("Alice", r.after().get("name"));
        assertEquals("00000025:00000338:0003", r.commitLsn());
        assertEquals("00000025:00000338:0002", r.changeLsn());
        assertTrue(r.isSnapshot());
    }

    @Test
    void parse_nonSnapshotRecord_isNotSnapshot() throws Exception {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "false"},
                "op": "c"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertFalse(r.isSnapshot());
    }

    @Test
    void parse_lastSnapshot_isSnapshot() throws Exception {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "last"},
                "op": "r"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
    }

    @Test
    void parse_nullAfter_afterIsNull() throws Exception {
        String json = """
        {
            "payload": {
                "after": null,
                "source": {"snapshot": "true"},
                "op": "d"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.after());
    }
}
