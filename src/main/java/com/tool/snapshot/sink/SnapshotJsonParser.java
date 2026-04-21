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
            String op, String commitLsn, String changeLsn, String snapshot
    ) {
        public boolean isSnapshot() { return "true".equals(snapshot) || "last".equals(snapshot); }
    }

    public ParsedRecord parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        JsonNode source = payload.get("source");

        String dbName = source != null ? source.path("db").asText() : null;
        String schema = source != null ? source.path("schema").asText() : null;
        String table = source != null ? source.path("table").asText() : null;
        String commitLsn = source != null ? source.path("commit_lsn").asText(null) : null;
        String changeLsn = source != null ? source.path("change_lsn").asText(null) : null;
        String snapshot = source != null ? source.path("snapshot").asText(null) : null;
        String op = payload.path("op").asText(null);

        Map<String, Object> after = toMap(payload.get("after"));
        Map<String, Object> before = toMap(payload.get("before"));

        return new ParsedRecord(dbName, schema, table, before, after,
                op, commitLsn, changeLsn, snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return mapper.convertValue(node, LinkedHashMap.class);
    }
}
