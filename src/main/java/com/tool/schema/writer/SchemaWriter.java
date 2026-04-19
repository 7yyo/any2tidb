package com.tool.schema.writer;

import com.tool.common.model.ConversionResult;

import java.sql.Connection;

/**
 * Target-agnostic contract for applying DDL to a destination database.
 * Decouples pipeline steps from TiDB so future writers (e.g. PostgresWriter)
 * can be wired in without touching step code.
 */
public interface SchemaWriter {
    /** Execute the given DDL against {@code conn}; update {@code result} with outcome. */
    void executeDDL(Connection conn, String ddl, ConversionResult result) throws Exception;
}
