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
     * @param schema    source schema name (e.g. {@code "dbo"})
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

    /**
     * Compute PK-range chunks for a table.
     * For tables without a primary key, returns a single PkRange with no whereClause.
     * For tables with a numeric single-column PK, splits by arithmetic value range.
     * For other PK types, uses ORDER BY + OFFSET/FETCH boundaries.
     *
     * @param conn      connection to the database
     * @param dbName    database name
     * @param schema    schema name
     * @param table     table name
     * @param chunkSize rows per chunk (determines number of splits)
     * @return ordered list of PkRange chunks
     */
    List<PkRange> computePkRanges(Connection conn, String dbName, String schema,
                                   String table, int chunkSize) throws Exception;

    /**
     * Stream rows matching a specific PK range to {@code consumer}.
     * The extractor opens and closes its own {@link java.sql.PreparedStatement} and
     * {@link java.sql.ResultSet}; the caller owns {@code conn}.
     *
     * @param conn      open, ready-to-use connection
     * @param range     PK range descriptor from {@link #computePkRanges}
     * @param batchSize rows per consumer callback
     * @param consumer  called once per batch; must not be null
     */
    void streamTableRange(Connection conn, PkRange range, int batchSize,
                          RowBatchConsumer consumer) throws Exception;
}
