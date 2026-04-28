package com.tool.schema.extractor;

import com.tool.common.model.TableSchema;

import java.sql.Connection;
import java.util.List;

/**
 * Source-agnostic contract for reading schema metadata.
 * Decouple pipeline steps from the source database implementation so future
 * extractors (e.g. PostgresExtractor) can be wired in without touching step code.
 */
public interface SchemaExtractor {
    /** Return all user-database names visible to this connection. */
    List<String> listDatabases(Connection conn) throws Exception;

    /**
     * List tables matching the given schema/name filters.
     * Each entry is a two-element array: [schemaName, tableName].
     * Empty lists mean "no filter" (return all).
     */
    List<String[]> listTables(Connection conn, List<String> databases, List<String> tables) throws Exception;

    /** Extract full column + index metadata for one table. */
    TableSchema extractTable(Connection conn, String schema, String table) throws Exception;

    /**
     * Return the ordered list of primary key column names for one table.
     * Returns an empty list if the table has no primary key.
     */
    List<String> getPrimaryKeyColumns(Connection conn, String schema, String table) throws Exception;
}
