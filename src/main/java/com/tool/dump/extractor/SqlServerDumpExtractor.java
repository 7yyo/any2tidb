package com.tool.dump.extractor;

import static com.tool.common.SqlUtils.escapeBracket;

import com.tool.schema.extractor.SchemaExtractor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    private static final Set<String> NUMERIC_PK_TYPES = Set.of(
            "int", "integer", "bigint", "smallint", "tinyint", "bit",
            "decimal", "numeric", "money", "smallmoney");

    private final SchemaExtractor schemaExtractor;

    public SqlServerDumpExtractor(SchemaExtractor schemaExtractor) {
        this.schemaExtractor = schemaExtractor;
    }

    @Override
    public void streamTable(Connection conn, String schema, String table,
                            int chunkSize, RowBatchConsumer consumer) throws Exception {
        streamTable(conn, schema, table, chunkSize, true, consumer);
    }

    @Override
    public void streamTable(Connection conn, String schema, String table,
                            int chunkSize, boolean useNolock,
                            RowBatchConsumer consumer) throws Exception {
        String hint = useNolock ? " WITH (NOLOCK)" : "";
        String sql = "SELECT * FROM [" + escapeBracket(schema) + "].[" + escapeBracket(table) + "]" + hint;
        streamSql(conn, sql, chunkSize, consumer);
    }

    @Override
    public List<String> getColumnNames(Connection conn, String schema, String table) throws Exception {
        return getColumnNames(conn, schema, table, true);
    }

    @Override
    public List<String> getColumnNames(Connection conn, String schema, String table,
                                       boolean useNolock) throws Exception {
        String hint = useNolock ? " WITH (NOLOCK)" : "";
        String sql = "SELECT * FROM [" + escapeBracket(schema) + "].[" + escapeBracket(table) + "]" + hint + " WHERE 1=0";
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
        String sql =
            "SELECT SUM(p.rows) " +
            "FROM sys.tables t " +
            "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
            "JOIN sys.partitions p ON t.object_id = p.object_id " +
            "WHERE s.name = ? AND t.name = ? " +
            "  AND p.index_id IN (0, 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0L;
                long count = rs.getLong(1);
                return Math.max(0L, count);
            }
        }
    }

    // ── PK-range chunking ────────────────────────────────────────────────────

    @Override
    public List<PkRange> computePkRanges(Connection conn, String dbName,
                                          String schema, String table,
                                          int chunkSize) throws Exception {
        List<String> pkCols = schemaExtractor.getPrimaryKeyColumns(conn, schema, table);

        // No PK → single chunk, full table scan
        if (pkCols.isEmpty()) {
            return List.of(new PkRange(dbName, schema, table, List.of(), 0, null, null));
        }

        long estimatedRows = estimateRowCount(conn, schema, table);
        int numChunks = (int) Math.max(1, (estimatedRows + chunkSize - 1) / chunkSize);

        if (numChunks <= 1) {
            return List.of(new PkRange(dbName, schema, table, pkCols, 0, null, null));
        }

        String pkCol = pkCols.get(0);
        String colType = getColumnType(conn, schema, table, pkCol);

        // Single-column numeric PK → range-based split (uses index seeks)
        if (pkCols.size() == 1 && colType != null && NUMERIC_PK_TYPES.contains(colType)) {
            return computeNumericRanges(conn, dbName, schema, table, pkCols, pkCol,
                    numChunks, chunkSize);
        }

        // All other cases → OFFSET/FETCH (works for any type, any number of columns)
        return computeOffsetRanges(dbName, schema, table, pkCols, numChunks, chunkSize);
    }

    @Override
    public void streamTableRange(Connection conn, PkRange range, int batchSize,
                                  RowBatchConsumer consumer) throws Exception {
        String schema = range.schema();
        String table = range.table();
        String bracketed = "[" + escapeBracket(schema) + "].[" + escapeBracket(table) + "]";

        String sql;
        if (range.whereClause() == null && range.params() == null) {
            sql = "SELECT * FROM " + bracketed;
        } else if (range.whereClause() != null && range.whereClause().startsWith("OFFSET ")) {
            // OFFSET/FETCH variant: requires ORDER BY
            StringBuilder orderBy = new StringBuilder();
            for (int i = 0; i < range.pkColumns().size(); i++) {
                if (i > 0) orderBy.append(", ");
                orderBy.append("[").append(escapeBracket(range.pkColumns().get(i))).append("]");
            }
            sql = "SELECT * FROM " + bracketed + " ORDER BY " + orderBy + " " + range.whereClause();
        } else {
            sql = "SELECT * FROM " + bracketed + " WHERE " + range.whereClause();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            if (range.params() != null) {
                for (int i = 0; i < range.params().length; i++) {
                    ps.setObject(i + 1, range.params()[i]);
                }
            }
            ps.setFetchSize(batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                int colCount = rs.getMetaData().getColumnCount();
                List<Object[]> batch = new ArrayList<>(batchSize);
                int batchIndex = 0;

                while (rs.next()) {
                    Object[] row = new Object[colCount];
                    for (int c = 1; c <= colCount; c++) {
                        row[c - 1] = rs.getObject(c);
                    }
                    batch.add(row);

                    if (batch.size() == batchSize) {
                        consumer.accept(new RowBatch(List.copyOf(batch), batchIndex++));
                        batch.clear();
                    }
                }

                if (!batch.isEmpty()) {
                    consumer.accept(new RowBatch(List.copyOf(batch), batchIndex));
                }
            }
        }
    }

    // ── Numeric PK range split ───────────────────────────────────────────────

    private List<PkRange> computeNumericRanges(Connection conn, String dbName,
                                                String schema, String table,
                                                List<String> pkCols, String pkCol,
                                                int numChunks, int chunkSize) throws Exception {
        String bracketed = "[" + escapeBracket(schema) + "].[" + escapeBracket(table) + "]";
        String bracketedCol = "[" + escapeBracket(pkCol) + "]";

        // Get min/max
        String sql = "SELECT MIN(" + bracketedCol + "), MAX(" + bracketedCol + ") FROM " + bracketed;
        BigDecimal min = null, max = null;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                min = toBigDecimal(rs.getObject(1));
                max = toBigDecimal(rs.getObject(2));
            }
        }

        // Empty table
        if (min == null || max == null) {
            return List.of(new PkRange(dbName, schema, table, pkCols, 0, null, null));
        }

        // Single chunk
        if (numChunks <= 1) {
            return List.of(new PkRange(dbName, schema, table, pkCols, 0, null, null));
        }

        BigDecimal range = max.subtract(min).add(BigDecimal.ONE);
        BigDecimal step = range.divide(BigDecimal.valueOf(numChunks), 0, java.math.RoundingMode.CEILING);

        List<PkRange> ranges = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            BigDecimal lo = min.add(step.multiply(BigDecimal.valueOf(i)));
            BigDecimal hi = min.add(step.multiply(BigDecimal.valueOf(i + 1)));
            // Last chunk: use exclusive upper bound without cap
            if (i == numChunks - 1) {
                hi = max.add(BigDecimal.ONE);
            }
            String where = "([" + escapeBracket(pkCol) + "] >= ? AND [" + escapeBracket(pkCol) + "] < ?)";
            ranges.add(new PkRange(dbName, schema, table, pkCols, i, where,
                    new Object[]{lo, hi}));
        }
        return ranges;
    }

    // ── OFFSET/FETCH range split ─────────────────────────────────────────────

    private List<PkRange> computeOffsetRanges(String dbName, String schema, String table,
                                               List<String> pkCols, int numChunks,
                                               int chunkSize) {
        List<PkRange> ranges = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            int offset = i * chunkSize;
            // OFFSET ... ROWS FETCH NEXT ... ROWS ONLY
            String where = "OFFSET " + offset + " ROWS FETCH NEXT " + chunkSize + " ROWS ONLY";
            ranges.add(new PkRange(dbName, schema, table, pkCols, i, where, null));
        }
        return ranges;
    }

    // ── Shared cursor streaming ──────────────────────────────────────────────

    private void streamSql(Connection conn, String sql, int chunkSize,
                           RowBatchConsumer consumer) throws Exception {
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

                if (!batch.isEmpty()) {
                    consumer.accept(new RowBatch(List.copyOf(batch), batchIndex));
                }
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** Get the lowercased type name of a single column from ResultSetMetaData. */
    private String getColumnType(Connection conn, String schema, String table,
                                  String column) throws Exception {
        String sql = "SELECT [" + escapeBracket(column) + "] FROM ["
                + escapeBracket(schema) + "].[" + escapeBracket(table) + "] WHERE 1=0";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            String typeName = rs.getMetaData().getColumnTypeName(1);
            return typeName != null ? typeName.toLowerCase() : null;
        }
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.longValue());
        return new BigDecimal(val.toString());
    }

}
