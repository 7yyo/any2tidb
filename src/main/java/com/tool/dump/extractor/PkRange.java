package com.tool.dump.extractor;

import java.util.List;

/**
 * Describes one contiguous chunk of a table, split by primary key range.
 * For tables without a PK, there is exactly one range covering the whole table.
 *
 * @param dbName      source database name
 * @param schema      schema name (e.g. "dbo")
 * @param table       table name
 * @param pkColumns   ordered list of PK column names; empty for no-PK tables
 * @param chunkIndex  global 0-based chunk index, unique per (dbName, table);
 *                    maps directly to the CSV file chunk number
 * @param whereClause SQL WHERE clause fragment (without the leading "WHERE"),
 *                    e.g. "([id]) >= ? AND ([id]) < ?";
 *                    null for no-PK single-chunk tables
 * @param params      bound parameters for the whereClause placeholders;
 *                    null when whereClause is null
 */
public record PkRange(
        String dbName,
        String schema,
        String table,
        List<String> pkColumns,
        int chunkIndex,
        String whereClause,
        Object[] params
) {}
