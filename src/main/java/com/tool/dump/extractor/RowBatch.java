package com.tool.dump.extractor;

import java.util.List;

/**
 * One batch of rows fetched from SQL Server.
 *
 * @param rows       each element is one row; columns in the same order as
 *                   {@link DumpExtractor#getColumnNames} returned.
 * @param batchIndex 0-based sequence number within a single table export.
 */
public record RowBatch(List<Object[]> rows, int batchIndex) {}
