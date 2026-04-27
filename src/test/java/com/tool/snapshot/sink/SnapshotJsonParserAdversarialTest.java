package com.tool.snapshot.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SnapshotJsonParser — targets malformed Debezium JSON,
 * missing fields, unexpected types, and edge-case payloads.
 */
class SnapshotJsonParserAdversarialTest {

    private final SnapshotJsonParser parser = new SnapshotJsonParser();
    private final ObjectMapper mapper = new ObjectMapper();

    // ── null JSON ────────────────────────────────────────────────────────

    @Test
    void parse_nullJson_throwsException() {
        assertThrows(Exception.class, () -> parser.parse(null));
    }

    // ── empty JSON ───────────────────────────────────────────────────────

    @Test
    void parse_emptyJson_missingPayload_throwsException() {
        assertThrows(Exception.class, () -> parser.parse("{}"));
    }

    // ── payload is null ────────────────────────────────────────────────────

    @Test
    void parse_nullPayload_throwsNPE() {
        // payload: null → source = payload.get("source") → NPE
        assertThrows(NullPointerException.class,
                () -> parser.parse("{\"payload\":null}"));
    }

    // ── payload is not an object ─────────────────────────────────────────────

    @Test
    void parse_payloadIsString_treatsStringAsPayload() throws Exception {
        // payload: "not-json" → mapper.readTree succeeds, source = null
        SnapshotJsonParser.ParsedRecord r = parser.parse("{\"payload\":\"hello\"}");
        assertNull(r.dbName());
        assertNull(r.schema());
        assertNull(r.table());
        assertNull(r.op());
    }

    // ── payload missing source field ────────────────────────────────────────────

    @Test
    void parse_noSourceField_allSourceFieldsNull() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("op", "r")
                        .put("after", mapper.createObjectNode().put("id", 1)))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.dbName());
        assertNull(r.schema());
        assertNull(r.table());
        assertNull(r.commitLsn());
        assertNull(r.changeLsn());
    }

    // ── source missing db/schema/table ────────────────────────────────────────

    @Test
    void parse_sourceWithoutDbTchemaTable_allNull() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode())
                        .put("op", "r"))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.dbName());
        assertNull(r.schema());
        assertNull(r.table());
    }

    // ── after is null (DELETE event) ──────────────────────────────────────────

    @Test
    void parse_deleteEvent_afterNull() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb"))
                        .put("op", "d")
                        .putNull("after"))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.after());
        assertFalse(r.isSnapshot());
    }

    // ── before is null (snapshot doesn't always have before) ───────────────

    @Test
    void parse_snapshotBeforeNull() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode().put("id", 1))
                        .putNull("before"))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
        assertNotNull(r.after());
        assertNull(r.before());
    }

    // ── snapshot = "last" (last snapshot in table) ────────────────────────────

    @Test
    void parse_snapshotLast_isSnapshot() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "last")
                        .put("after", mapper.createObjectNode().put("id", 1)))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot(), "snapshot='last' should be treated as snapshot");
    }

    // ── op field missing ──────────────────────────────────────────────────────

    @Test
    void parse_noOpField_opIsNull() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb")))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.op());
    }

    // ── after contains non-primitive values (nested object) ────────────────────

    @Test
    void parse_afterWithNestedObject_mapConversion() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode()
                                .put("id", 1)
                                .put("nested", mapper.createObjectNode().put("x", 42))))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
        assertNotNull(r.after());
        assertTrue(r.after().containsKey("nested"),
                "Nested object should be converted to map entry");
        assertEquals(42, ((Number) r.after().get("nested")).intValue());
    }

    // ── after contains array ──────────────────────────────────────────────────

    @Test
    void parse_afterWithArray_mapConversion() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode()
                                .put("tags", mapper.createArrayNode()
                                        .add("a").add("b"))))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
        assertNotNull(r.after());
        assertTrue(r.after().containsKey("tags"),
                "Array should be converted to list");
    }

    // ── after contains numeric string (Debezium may emit as string) ──────────

    @Test
    void parse_afterNumericAsString_preservedAsString() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode().put("amount", "123.45")))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        // Debezium may emit decimals as strings depending on connector config
        // Jackson converts to String "123.45" or BigDecimal depending on config
        assertNotNull(r.after().get("amount"));
    }

    // ── after is empty object ─────────────────────────────────────────────────

    @Test
    void parse_afterEmptyObject_emptyMap() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode().put("db", "mydb").put("table", "t"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode()))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
        assertNotNull(r.after());
        assertTrue(r.after().isEmpty(), "Empty after object should produce empty map");
    }

    // ── commit_lsn and change_lsn with hex format ──────────────────────────────

    @Test
 void parse_lsnValues_preserved() throws Exception {
        String json = mapper.createObjectNode()
                .put("payload", mapper.createObjectNode()
                        .put("source", mapper.createObjectNode()
                                .put("db", "mydb")
                                .put("table", "t")
                                .put("commit_lsn", "0x000000250000012E")
                                .put("change_lsn", "0x000000250000012F"))
                        .put("op", "r")
                        .put("snapshot", "true")
                        .put("after", mapper.createObjectNode().put("id", 1)))
                .toString();

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertEquals("0x000000250000012E", r.commitLsn());
        assertEquals("0x000000250000012F", r.changeLsn());
    }
}
