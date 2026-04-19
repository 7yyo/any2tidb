package com.tool.dump.extractor;

import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads SQL Server tables using a server-side cursor to guarantee
 * bounded memory usage regardless of table size.
 *
 * <p>Key JDBC settings that enable server-side (adaptive-buffering) mode:
 * <ul>
 *   <li>{@link ResultSet#TYPE_FORWARD_ONLY} + {@link ResultSet#CONCUR_READ_ONLY}</li>
 *   <li>{@link PreparedStatement#setFetchSize(int)} with a non-zero value</li>
 * </ul>
 */
@Component
public class SqlServerDumpExtractor implements DumpExtractor {

    @Override
    public void streamTable(Connection conn, String schema, String table,
                            int chunkSize, RowBatchConsumer consumer) throws Exception {
        String sql = "SELECT * FROM [" + schema + "].[" + table + "] WITH (NOLOCK)";

        try (PreparedStatement ps = conn.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            ps.setFetchSize(chunkSize);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<Object[]> batch = new ArrayList<>(chunkSize);
                int batchIndex = 0;

                while (rs.next()) {
                    Object[] row = new Object[colCount];
                    for (int c = 1; c <= colCount; c++) {
                        row[c - 1] = rs.getObject(c);
                    }
                    batch.add(row);

                    if (batch.size() == chunkSize) {
                        consumer.accept(new RowBatch(List.copyOf(batch), batchIndex++));
                        batch.clear();
                    }
                }

                // Flush remaining rows
                if (!batch.isEmpty()) {
                    consumer.accept(new RowBatch(List.copyOf(batch), batchIndex));
                }
            }
        }
    }

    @Override
    public List<String> getColumnNames(Connection conn, String schema, String table) throws Exception {
        String sql = "SELECT * FROM [" + schema + "].[" + table + "] WITH (NOLOCK) WHERE 1=0";
        try (PreparedStatement ps = conn.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> cols = new ArrayList<>(meta.getColumnCount());
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                cols.add(meta.getColumnName(i));
            }
            return cols;
        }
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String table) throws Exception {
        // Use SQL Server statistics for a fast approximate count (no full scan)
        String sql =
            "SELECT SUM(p.rows) " +
            "FROM sys.tables t " +
            "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
            "JOIN sys.partitions p ON t.object_id = p.object_id " +
            "WHERE s.name = ? AND t.name = ? " +
            "  AND p.index_id IN (0, 1)"; // heap or clustered index
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
