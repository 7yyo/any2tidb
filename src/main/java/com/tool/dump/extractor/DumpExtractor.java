package com.tool.dump.extractor;

import java.sql.Connection;
import java.util.List;

/**
 * Source-agnostic contract for streaming row data out of a relational database.
 * Implementations must never load an entire table into memory; rows must be
 * delivered via {@link RowBatchConsumer} in fixed-size batches.
 */
public interface DumpExtractor {

    /**
     * Stream all rows of one table to {@code consumer} in batches of {@code chunkSize}.
     * The extractor opens and closes its own {@link java.sql.PreparedStatement} and
     * {@link java.sql.ResultSet}; the caller owns {@code conn}.
     *
     * @param conn      open, ready-to-use connection scoped to the target database
     * @param schema    SQL Server schema name (e.g. {@code "dbo"})
     * @param table     table name
     * @param chunkSize maximum rows per batch (last batch may be smaller)
     * @param consumer  called once per batch; must not be {@code null}
     */
    void streamTable(Connection conn, String schema, String table,
                     int chunkSize, RowBatchConsumer consumer) throws Exception;

    /**
     * Variant that controls whether {@code WITH (NOLOCK)} is appended to the query.
     * Default delegates to {@link #streamTable(Connection, String, String, int, RowBatchConsumer)}
     * with {@code useNolock = true} for backwards compatibility.
     */
    default void streamTable(Connection conn, String schema, String table,
                             int chunkSize, boolean useNolock,
                             RowBatchConsumer consumer) throws Exception {
        streamTable(conn, schema, table, chunkSize, consumer);
    }

    /**
     * Return the ordered list of column names for {@code schema.table}.
     * Order must match the column order delivered by {@link #streamTable}.
     */
    List<String> getColumnNames(Connection conn, String schema, String table) throws Exception;

    /**
     * Variant that controls whether {@code WITH (NOLOCK)} is appended to the query.
     * Default delegates to {@link #getColumnNames(Connection, String, String)}.
     */
    default List<String> getColumnNames(Connection conn, String schema, String table,
                                        boolean useNolock) throws Exception {
        return getColumnNames(conn, schema, table);
    }

    /**
     * Return a fast, approximate row count for progress reporting.
     * May use statistics tables; precision is not guaranteed.
     */
    long estimateRowCount(Connection conn, String schema, String table) throws Exception;
}
