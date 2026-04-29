package com.tool.snapshot.sink;

import com.tool.logging.StructuredLogger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TiDBBatchWriter {

    private final DataSource dataSource;
    private final SinkRecordConverter converter;
    private final int batchSize;
    private final StructuredLogger log;

    private final Map<String, List<Map<String, Object>>> buffer = new HashMap<>();
    private final Map<String, List<String>> fieldOrders = new HashMap<>();
    private final Map<String, String> tableDbNames = new HashMap<>();
    private long totalRows = 0L;
    private long lastLogRows = 0L;
    private long lastLogMs = System.currentTimeMillis();
    private static final long LOG_INTERVAL_ROWS = 100_000;
    private static final long LOG_INTERVAL_MS = 15_000;
    private final Map<String, Long> tableRowCounts = new HashMap<>();

    public TiDBBatchWriter(DataSource dataSource, SinkRecordConverter converter, int batchSize,
                           StructuredLogger log) {
        this.dataSource = dataSource;
        this.converter = converter;
        this.batchSize = batchSize;
        this.log = log;
    }

    public void accumulate(String dbName, String table, Map<String, Object> after) {
        tableDbNames.putIfAbsent(table, dbName);
        buffer.computeIfAbsent(table, k -> new ArrayList<>()).add(after);
        if (!fieldOrders.containsKey(table)) {
            fieldOrders.put(table, new ArrayList<>(new LinkedHashMap<>(after).keySet()));
        }
        totalRows++;
        tableRowCounts.merge(table, 1L, Long::sum);

        long now = System.currentTimeMillis();
        if (totalRows - lastLogRows >= LOG_INTERVAL_ROWS || now - lastLogMs >= LOG_INTERVAL_MS) {
            log.log("INFO", "snapshot progress", "rows", totalRows, "tables", tableRowCounts.size());
            lastLogRows = totalRows;
            lastLogMs = now;
        }

        if (buffer.get(table).size() >= batchSize) {
            try {
                flushTable(table);
            } catch (Exception e) {
                log.log("ERROR", "flush failed", "table", tableDbNames.getOrDefault(table, "?") + "." + table, "error", e.getMessage());
            }
        }
    }

    public void flushAll() throws SQLException {
        for (String table : new ArrayList<>(buffer.keySet())) {
            if (!buffer.get(table).isEmpty()) {
                flushTable(table);
            }
            long rows = tableRowCounts.getOrDefault(table, 0L);
            String dbName = tableDbNames.getOrDefault(table, "?");
            log.log("INFO", "table complete", "table", dbName + "." + table, "rows", rows);
        }
        totalRows = 0;
        tableRowCounts.clear();
    }

    private void flushTable(String table) throws SQLException {
        List<Map<String, Object>> rows = buffer.get(table);
        if (rows == null || rows.isEmpty()) return;

        String dbName = tableDbNames.getOrDefault(table, "unknown");
        List<String> fields = fieldOrders.get(table);
        String cols = String.join(", ", fields.stream().map(f -> "`" + escapeBacktick(f) + "`").toList());
        String placeholders = String.join(", ", fields.stream().map(f -> "?").toList());
        String sql = "INSERT INTO `" + escapeBacktick(dbName) + "`.`" + escapeBacktick(table) + "` (" + cols + ") VALUES (" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                converter.bind(ps, dbName, table, row, fields);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        rows.clear();
    }

    public long getTotalRows() { return totalRows; }

    private static String escapeBacktick(String s) {
        return s.replace("`", "``");
    }

    public Map<String, Long> getTableRows() { return new HashMap<>(tableRowCounts); }
}
