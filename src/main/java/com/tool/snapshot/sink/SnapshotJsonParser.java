package com.tool.snapshot.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class SnapshotJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public record ParsedRecord(
            String dbName, String schema, String table,
            Map<String, Object> before, Map<String, Object> after,
            String op, String snapshot
    ) {
        public boolean isSnapshot() {
            // Debezium 3.x snapshot field values:
            //   "true" / "first" / "first_in_data_collection" — data rows
            //   "last" / "last_in_data_collection" — last row of chunk
            //   "false" — streaming
            return snapshot != null && !"false".equals(snapshot);
        }
    }

    public ParsedRecord parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        JsonNode source = payload.get("source");

        String dbName = source != null ? source.path("db").asText() : null;
        String schema = source != null ? source.path("schema").asText() : null;
        String table = source != null ? source.path("table").asText() : null;
        String snapshot = source != null ? source.path("snapshot").asText(null) : null;
        String op = payload.path("op").asText(null);

        Map<String, Object> after = toMap(payload.get("after"));
        Map<String, Object> before = toMap(payload.get("before"));

        return new ParsedRecord(dbName, schema, table, before, after,
                op, snapshot);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return new ObjectMapper().convertValue(node, LinkedHashMap.class);
    }

    /**
     * Parse a Debezium event.key() JSON string into a Map of PK column names to values.
     * The key JSON format is: {"payload":{"pk_col1":val1,"pk_col2":val2}}
     */
    public Map<String, Object> parseKey(String keyJson) throws Exception {
        if (keyJson == null || keyJson.isEmpty()) return null;
        JsonNode root = mapper.readTree(keyJson);
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        return toMap(payload);
    }
}
