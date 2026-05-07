package com.tool.schema.extractor;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.TableSchema;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.converter.TypeMapper;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

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
     * null or empty lists mean "no filter" (return all).
     */
    List<String[]> listTables(Connection conn, List<String> schemas, List<String> tables) throws Exception;

    /** List all tables across all user schemas, optionally filtered by table name. */
    default List<String[]> listTables(Connection conn, List<String> tables) throws Exception {
        return listTables(conn, null, tables);
    }

    /**
     * Fast approximate row counts for the given tables.
     * Returns map of {@code "tableName" → estimatedRows}.
     * Default returns empty map (unavailable for this source).
     */
    default Map<String, Long> estimateRowCounts(Connection conn, List<String[]> tables) throws Exception {
        return Map.of();
    }

    /** Extract full column + index metadata for one table. */
    TableSchema extractTable(Connection conn, String schema, String table) throws Exception;

    /**
     * Return the ordered list of primary key column names for one table.
     * Returns an empty list if the table has no primary key.
     */
    List<String> getPrimaryKeyColumns(Connection conn, String schema, String table) throws Exception;

    /**
     * Generate CREATE TABLE DDL for one table.
     * Default: extract + convert pipeline (SQL Server path).
     * Sources with native DDL (MySQL SHOW CREATE TABLE) override this.
     */
    default String generateCreateTableDDL(Connection conn, String schema, String table,
                                          TypeMapper typeMapper, ConversionResult result,
                                          boolean dropIfExists) throws Exception {
        TableSchema ts = extractTable(conn, schema, table);
        return new SchemaConverter(typeMapper).toCreateTableDDL(ts, result, dropIfExists);
    }

    // ── Views / Procedures / Functions / Triggers ─────────────────────────

    default List<String> listViews(Connection conn, String database) throws Exception { return List.of(); }
    default List<String> listProcedures(Connection conn, String database) throws Exception { return List.of(); }
    default List<String> listFunctions(Connection conn, String database) throws Exception { return List.of(); }
    default List<String> listTriggers(Connection conn, String database) throws Exception { return List.of(); }

    default String generateViewDDL(Connection conn, String database, String view,
                                   ConversionResult result) throws Exception { return null; }
    default String generateProcedureDDL(Connection conn, String database, String proc,
                                        ConversionResult result) throws Exception { return null; }
    default String generateFunctionDDL(Connection conn, String database, String func,
                                       ConversionResult result) throws Exception { return null; }
    default String generateTriggerDDL(Connection conn, String database, String trigger,
                                      ConversionResult result) throws Exception { return null; }
}
