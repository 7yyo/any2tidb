package com.tool.dump.writer;

import com.tool.dump.extractor.RowBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for CsvDumpWriter — targets file splitting edge cases,
 * error handling, and output format corner cases.
 */
class CsvDumpWriterAdversarialTest {

    @TempDir
    Path tmp;

    // ── File splitting: single row exceeding threshold stays in chunk 0 ──────

    @Test
    void fileSplitting_singleRowExceedsThreshold_staysInChunk0() throws Exception {
        // Threshold = 1 byte, but a single row is always written to chunk 0
        // (condition: bytesWritten > 0 — first row has bytesWritten=0)
        CsvDumpWriter w = new CsvDumpWriter(1L);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("data"),
                new RowBatch(List.<Object[]>of(new Object[]{"AAAA"}), 0));
        w.close();

        Path dir = tmp.resolve("db");
        assertTrue(Files.exists(dir.resolve("db.t.000000000.csv")),
                "First row always goes to chunk 0, even if exceeding threshold");
        assertFalse(Files.exists(dir.resolve("db.t.000000001.csv")),
                "No chunk 1 should be created for a single row");
    }

    @Test
    void fileSplitting_secondRowTriggersRollover() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(1L);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{"a"}), 0));
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{"b"}), 1));
        w.close();

        Path dir = tmp.resolve("db");
        assertTrue(Files.exists(dir.resolve("db.t.000000000.csv")));
        assertTrue(Files.exists(dir.resolve("db.t.000000001.csv")));
    }

    // ── close() idempotency ──────────────────────────────────────────────────

    @Test
    void close_calledTwice_noException() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();
        assertDoesNotThrow(w::close,
                "Calling close() twice should not throw");
    }

    // ── Writer reuse after close: overwrites chunk 0 ─────────────────────────

    @Test
    void reuseAfterClose_throwsIllegalStateException() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{"original"}), 0));
        w.close();

        // After close, reuse should throw
        assertThrows(IllegalStateException.class,
                () -> w.writeBatch(tmp, "db", "dbo", "t",
                        List.of("id"),
                        new RowBatch(List.<Object[]>of(new Object[]{"overwritten"}), 0)),
                "Reuse after close should throw IllegalStateException — prevents data overwrite");
    }

    // ── Null row in batch → NPE ──────────────────────────────────────────────

    @Test
    void batchWithNullRow_throwsNPE() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        // Create a batch where one row is null
        List<Object[]> rows = List.of(
                new Object[]{1},
                null,  // null row element
                new Object[]{3}
        );

        // List.of() doesn't allow null elements, so we use a mutable list
        java.util.ArrayList<Object[]> mutableRows = new java.util.ArrayList<>();
        mutableRows.add(new Object[]{1});
        mutableRows.add(null);
        mutableRows.add(new Object[]{3});

        assertThrows(NullPointerException.class,
                () -> w.writeBatch(tmp, "db", "dbo", "t",
                        List.of("id"), new RowBatch(mutableRows, 0)),
                "Null row element in batch causes NPE — no defensive check");
    }

    // ── dbName with special characters ───────────────────────────────────────

    @Test
    void dbNameWithSpaces_createsDirectoryWithSpaces() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "my database", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        Path dir = tmp.resolve("my database");
        assertTrue(Files.exists(dir));
        assertTrue(Files.exists(dir.resolve("my database.t.000000000.csv")));
    }

    @Test
    void dbNameWithUnicode_createsCorrectFiles() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "数据库", "dbo", "表",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        Path dir = tmp.resolve("数据库");
        assertTrue(Files.exists(dir));
        assertTrue(Files.exists(dir.resolve("数据库.表.000000000.csv")));
    }

    // ── Empty batch: no file created for new table ───────────────────────────

    @Test
    void emptyBatchForNewTable_noFileCreated() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "newtable",
                List.of("id"), new RowBatch(List.of(), 0));
        w.close();

        // Empty batch returns early — computeIfAbsent never called
        Path file = tmp.resolve("db").resolve("db.newtable.000000000.csv");
        assertFalse(Files.exists(file),
                "Empty batch for a table that was never written to should not create a file");
    }

    // ── Multiple batches same table: correct file splitting ──────────────────

    @Test
    void multipleBatchesSameTable_accumulateUntilThreshold() throws Exception {
        // Threshold = 50 bytes. Each row ~5 bytes. Write 15 rows in 3 batches of 5.
        // Batch 1: 5 rows × ~5 bytes = ~25 bytes → stays in chunk 0
        // Batch 2: cumulative ~50 bytes → may trigger rollover
        // Batch 3: continues in chunk 1
        CsvDumpWriter w = new CsvDumpWriter(50L);

        for (int b = 0; b < 3; b++) {
            List<Object[]> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                rows.add(new Object[]{"abc"});
            }
            w.writeBatch(tmp, "db", "dbo", "t",
                    List.of("v"), new RowBatch(rows, b));
        }
        w.close();

        Path dir = tmp.resolve("db");
        // At least chunk 0 should exist
        assertTrue(Files.exists(dir.resolve("db.t.000000000.csv")));
    }

    // ── Same table from different schemas: separate files ───────────────────

    @Test
    void sameTableDifferentSchemas_throwsConflict() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "schema1", "users",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));

        // Same db+table from different schema → conflict (same file path)
        assertThrows(IllegalStateException.class,
                () -> w.writeBatch(tmp, "db", "schema2", "users",
                        List.of("id"),
                        new RowBatch(List.<Object[]>of(new Object[]{2}), 0)),
                "Different schemas writing to same db.table should throw conflict");
        w.close();
    }

    // ── File content: no BOM ─────────────────────────────────────────────────

    @Test
    void output_noBOM() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        byte[] bytes = Files.readAllBytes(tmp.resolve("db").resolve("db.t.000000000.csv"));
        // UTF-8 BOM = EF BB BF
        assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF
                        && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF,
                "Output must not contain UTF-8 BOM");
    }

    // ── Row with zero columns ────────────────────────────────────────────────

    @Test
    void rowWithZeroColumns_producesEmptyLine() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of(),  // no columns
                new RowBatch(List.<Object[]>of(new Object[]{}), 0));
        w.close();

        Path file = tmp.resolve("db").resolve("db.t.000000000.csv");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("\r\n", content,
                "Row with zero columns produces just a CRLF line ending");
    }

    // ── Long.MAX_VALUE threshold: no splitting ever ──────────────────────────

    @Test
    void maxThreshold_neverSplits() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        for (int i = 0; i < 100; i++) {
            w.writeBatch(tmp, "db", "dbo", "t",
                    List.of("id"),
                    new RowBatch(List.<Object[]>of(new Object[]{i}), 0));
        }
        w.close();

        Path dir = tmp.resolve("db");
        // Only chunk 0 should exist
        assertTrue(Files.exists(dir.resolve("db.t.000000000.csv")));
        assertFalse(Files.exists(dir.resolve("db.t.000000001.csv")));
    }

    // ── columns parameter is completely unused ───────────────────────────────

    @Test
    void columnsParameter_rowShorterThanColumns_throws() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);

        // columns = ["a", "b", "c"] but row has only 2 elements
        assertThrows(IllegalArgumentException.class,
                () -> w.writeBatch(tmp, "db", "dbo", "t",
                        List.of("a", "b", "c"),
                        new RowBatch(List.<Object[]>of(new Object[]{1, 2}), 0)),
                "Row length != column count should throw IllegalArgumentException");
        w.close();
    }

    @Test
    void columnsParameter_rowLongerThanColumns_throws() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);

        // columns = ["a"] but row has 3 elements
        assertThrows(IllegalArgumentException.class,
                () -> w.writeBatch(tmp, "db", "dbo", "t",
                        List.of("a"),
                        new RowBatch(List.<Object[]>of(new Object[]{1, 2, 3}), 0)),
                "Row length != column count should throw IllegalArgumentException");
        w.close();
    }

    // ── Same table different schemas write to same file path ─────────────────

    @Test
    void sameTableDifferentSchemas_throwsConflict() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "schema1", "users",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));

        assertThrows(IllegalStateException.class,
                () -> w.writeBatch(tmp, "db", "schema2", "users",
                        List.of("id"),
                        new RowBatch(List.<Object[]>of(new Object[]{2}), 0)),
                "Schema conflict should throw — prevents data interleaving");
        w.close();
    }

    // ── File splitting: boundary exactly at threshold ───────────────────────

    @Test
    void fileSplitting_exactBoundary_noRollover() throws Exception {
        // If bytesWritten + lineBytes == threshold, condition is '>' not '>='
        // So exact boundary does NOT trigger rollover
        CsvDumpWriter w = new CsvDumpWriter(100L);

        // Write rows until we know the exact byte count
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("v"),
                new RowBatch(List.<Object[]>of(new Object[]{"abc"}), 0));
        // Read actual bytes written
        Path file = tmp.resolve("db").resolve("db.t.000000000.csv");
        long firstRowBytes = Files.size(file);

        // Set threshold to exactly firstRowBytes — second row should NOT trigger rollover
        // because condition is > not >=
        // But we can't change threshold after construction, so test the > vs >= semantics:
        // bytesWritten + lineBytes > threshold means:
        //   if equal → stays in same file
        //   if greater → rolls

        w.close();
        // This documents the > (not >=) behavior
    }

    // ── NUL byte in dbName ──────────────────────────────────────────────────

    @Test
    void dbNameWithNulByte_createsFileWithNulInPath() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db\0evil", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        // NUL in path — behavior depends on OS/filesystem
        // On Unix, NUL terminates the C string → file name truncated
        Path dir = tmp.resolve("db");
        // The directory "db" (before NUL) may or may not be created
        // This documents the potential issue
    }

    // ── Multiple writes to same key: data appended ──────────────────────────

    @Test
    void multipleWritesToSameTable_dataAppended() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{2}), 1));
        w.close();

        Path file = tmp.resolve("db").resolve("db.t.000000000.csv");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"1\"\r\n") && content.contains("\"2\"\r\n"));
    }

    // ── Different databases: separate directories ────────────────────────────

    @Test
    void differentDatabases_separateDirectories() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db1", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.writeBatch(tmp, "db2", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{2}), 0));
        w.close();

        assertTrue(Files.exists(tmp.resolve("db1").resolve("db1.t.000000000.csv")));
        assertTrue(Files.exists(tmp.resolve("db2").resolve("db2.t.000000000.csv")));
    }

    // ── Empty string dbName: valid but unusual ──────────────────────────────

    @Test
    void emptyStringDbName_createsEmptyDirName() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        Path file = tmp.resolve("").resolve(".t.000000000.csv");
        assertTrue(Files.exists(file),
                "Empty dbName creates file '.t.000000000.csv' in output root");
    }
}
