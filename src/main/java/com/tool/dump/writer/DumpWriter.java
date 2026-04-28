package com.tool.dump.writer;

import com.tool.dump.extractor.RowBatch;

import java.nio.file.Path;
import java.util.List;

/**
 * Writes batches of rows to the destination file system in some serialisation
 * format (currently Dumpling-compatible CSV).
 *
 * <p>One {@code DumpWriter} instance is used per table per thread. Callers
 * must invoke {@link #writeBatch} in ascending {@code batchIndex} order and
 * call {@link #close()} when the table is fully written.
 */
public interface DumpWriter {

    /**
     * Append {@code batch} to the output for {@code dbName.schema.table}.
     * The writer is responsible for creating subdirectories and splitting files
     * at the configured size threshold.
     *
     * @param outputDir root directory for all dump output
     * @param dbName    database name (used as sub-directory name)
     * @param schema    source schema (e.g. {@code "dbo"})
     * @param table     table name
     * @param columns   ordered list of column names matching {@code batch.rows()} column order
     * @param batch     the batch to write
     */
    void writeBatch(Path outputDir, String dbName, String schema, String table,
                    List<String> columns, RowBatch batch) throws Exception;

    /**
     * Flush and close all open file handles.  Must be called exactly once after
     * all batches for all tables have been written.
     */
    void close() throws Exception;

    /**
     * Returns the number of output files created by this writer.
     * After {@link #close()} this still returns the correct count.
     */
    int getFileCount();
}
