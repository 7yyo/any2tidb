package com.tool.dump.writer;

import com.tool.dump.extractor.RowBatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes row batches to Dumpling-compatible CSV files.
 *
 * <p>File naming: {@code <outputDir>/<dbName>/<dbName>.<table>.<9-digit-chunk>.csv}
 * (matches Dumpling/Lightning convention — no schema in file name).
 *
 * <p>Format:
 * <ul>
 *   <li>No header row</li>
 *   <li>Fields delimited by {@code ,}</li>
 *   <li>All non-NULL fields wrapped in {@code "}</li>
 *   <li>NULL fields → {@code \N} (no quotes)</li>
 *   <li>Internal {@code "} escaped as {@code ""}</li>
 *   <li>Line terminator: {@code \r\n}</li>
 *   <li>Encoding: UTF-8 without BOM</li>
 * </ul>
 *
 * <p>Thread-safety: {@link #writeBatch} is synchronized — one instance can be
 * shared across multiple threads writing chunks of the same table.
 */
public class CsvDumpWriter implements DumpWriter {

    private static final String CRLF = "\r\n";
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB

    /** Maximum bytes per file before rolling to the next chunk. */
    private final long fileSizeThresholdBytes;

    /** Initial chunk index for the first file per table (instead of 0). */
    private final int startChunkIndex;

    /** Tracks open-file state keyed by "dbName\0schema\0table". */
    private final Map<String, OpenFile> openFiles = new HashMap<>();

    /** Tracks "dbName\0table" to detect schema conflict (same table from different schemas). */
    private final Map<String, String> tableToSchema = new HashMap<>();

    /** Count of CSV files created across all tables written by this instance. */
    private int filesCreated = 0;

    private boolean closed = false;

    /**
     * @param fileSizeThresholdBytes roll to a new chunk file once the current
     *                               file exceeds this size; use {@link Long#MAX_VALUE}
     *                               to disable splitting.
     */
    public CsvDumpWriter(long fileSizeThresholdBytes) {
        this(fileSizeThresholdBytes, 0);
    }

    /**
     * @param fileSizeThresholdBytes roll to a new chunk file once the current
     *                               file exceeds this size; use {@link Long#MAX_VALUE}
     *                               to disable splitting.
     * @param startChunkIndex        initial chunk index for the first file per table;
     *                               use when multiple writers dump different PK ranges
     *                               of the same table concurrently.
     */
    public CsvDumpWriter(long fileSizeThresholdBytes, int startChunkIndex) {
        this.fileSizeThresholdBytes = fileSizeThresholdBytes;
        this.startChunkIndex = startChunkIndex;
    }

    @Override
    public synchronized void writeBatch(Path outputDir, String dbName, String schema, String table,
                           List<String> columns, RowBatch batch) throws Exception {
        if (closed) {
            throw new IllegalStateException("CsvDumpWriter is already closed");
        }
        if (batch.rows().isEmpty()) return;

        // Conflict detection: same db+table from different schemas → same file path
        String fileKey = dbName + '\0' + table;
        String prevSchema = tableToSchema.putIfAbsent(fileKey, schema);
        if (prevSchema != null && !prevSchema.equals(schema)) {
            throw new IllegalStateException(
                    "Schema conflict: " + prevSchema + "." + table + " and " + schema + "." + table
                    + " would write to the same file " + dbName + "." + table + ".csv");
        }

        String key = fileKey + '\0' + schema;
        boolean[] isNew = {false};
        OpenFile of = openFiles.computeIfAbsent(key, k -> {
            isNew[0] = true;
            return new OpenFile(outputDir, dbName, table, startChunkIndex);
        });
        if (isNew[0]) filesCreated++;

        for (Object[] row : batch.rows()) {
            if (columns != null && !columns.isEmpty() && row.length != columns.size()) {
                throw new IllegalArgumentException(
                        "Row length " + row.length + " != column count " + columns.size()
                        + " for " + schema + "." + table);
            }

            // Build CSV line
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(CsvValueFormatter.format(row[i]));
            }
            sb.append(CRLF);

            String line = sb.toString();
            byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);

            // Roll file if threshold exceeded (check before writing so the first
            // row always lands in chunk 0 even if it is larger than the threshold)
            if (of.bytesWritten > 0 && of.bytesWritten + lineBytes.length > fileSizeThresholdBytes) {
                of.close();
                of.chunkIndex++;
                of.bytesWritten = 0;
                of.open(outputDir, dbName, table);
                filesCreated++;
            }

            of.writer.write(line);
            of.bytesWritten += lineBytes.length;
        }
        of.writer.flush();
    }

    @Override
    public void close() throws Exception {
        closed = true;
        Exception first = null;
        for (OpenFile of : openFiles.values()) {
            try { of.close(); } catch (Exception e) { if (first == null) first = e; }
        }
        openFiles.clear();
        tableToSchema.clear();
        if (first != null) throw first;
    }

    /** Returns the number of CSV files created by this writer. */
    public int getFileCount() {
        return filesCreated;
    }

    // ── Internal state per table ──────────────────────────────────────────────

    private static final class OpenFile {
        int chunkIndex = 0;
        long bytesWritten = 0;
        BufferedWriter writer;

        OpenFile(Path outputDir, String dbName, String table, int startChunk) {
            this.chunkIndex = startChunk;
            open(outputDir, dbName, table);
        }

        void open(Path outputDir, String dbName, String table) {
            try {
                Path dir = outputDir.resolve(dbName);
                Files.createDirectories(dir);
                Path file = dir.resolve(fileName(dbName, table, chunkIndex));
                // OutputStreamWriter with explicit UTF-8 — no BOM
                writer = new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(file.toFile(), /* append= */ false),
                                StandardCharsets.UTF_8),
                        BUFFER_SIZE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void close() throws IOException {
            if (writer != null) { writer.flush(); writer.close(); writer = null; }
        }

        private static String fileName(String db, String table, int chunk) {
            return String.format("%s.%s.%09d.csv", db, table, chunk);
        }
    }
}
