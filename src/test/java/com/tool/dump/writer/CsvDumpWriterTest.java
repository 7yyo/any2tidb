package com.tool.dump.writer;

import com.tool.dump.extractor.RowBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvDumpWriterTest {

    @TempDir
    Path tmp;

    // ── basic write ──────────────────────────────────────────────────────────

    @Test
    void writeBatch_createsExpectedFile() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE); // no splitting
        List<Object[]> rows = List.of(
                new Object[]{"Alice", 30, null},
                new Object[]{"Bob",   25, "engineer"}
        );
        w.writeBatch(tmp, "mydb", "dbo", "users",
                List.of("name", "age", "role"),
                new RowBatch(rows, 0));
        w.close();

        Path file = tmp.resolve("mydb").resolve("mydb.users.000000000.csv");
        assertTrue(Files.exists(file), "CSV file must exist at " + file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("\"Alice\",\"30\",\\N", lines.get(0));
        assertEquals("\"Bob\",\"25\",\"engineer\"", lines.get(1));
    }

    @Test
    void writeBatch_usesWindowsLineEnding() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.close();

        byte[] bytes = Files.readAllBytes(tmp.resolve("db").resolve("db.t.000000000.csv"));
        // Every line must end with \r\n
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.endsWith("\r\n"), "File must use CRLF line endings");
    }

    @Test
    void writeBatch_nullField_outputsBackslashN() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("a", "b"),
                new RowBatch(List.<Object[]>of(new Object[]{null, "x"}), 0));
        w.close();

        String line = Files.readAllLines(
                tmp.resolve("db").resolve("db.t.000000000.csv"),
                StandardCharsets.UTF_8).get(0);
        assertEquals("\\N,\"x\"", line);
    }

    @Test
    void writeBatch_stringWithInternalQuote_escapesCorrectly() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("v"),
                new RowBatch(List.<Object[]>of(new Object[]{"say \"hi\""}), 0));
        w.close();

        String line = Files.readAllLines(
                tmp.resolve("db").resolve("db.t.000000000.csv"),
                StandardCharsets.UTF_8).get(0);
        assertEquals("\"say \"\"hi\"\"\"", line);
    }

    // ── file splitting ────────────────────────────────────────────────────────

    @Test
    void fileSplitting_createsNewFileWhenThresholdExceeded() throws Exception {
        // threshold of 1 byte: every batch triggers a new file
        CsvDumpWriter w = new CsvDumpWriter(1L);

        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{"a"}), 0));
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"),
                new RowBatch(List.<Object[]>of(new Object[]{"b"}), 1));
        w.close();

        Path dir = tmp.resolve("db");
        assertTrue(Files.exists(dir.resolve("db.t.000000000.csv")), "chunk 0 must exist");
        assertTrue(Files.exists(dir.resolve("db.t.000000001.csv")), "chunk 1 must exist");
    }

    @Test
    void multipleTables_separateFilesCreated() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "orders",
                List.of("id"), new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
        w.writeBatch(tmp, "db", "dbo", "customers",
                List.of("id"), new RowBatch(List.<Object[]>of(new Object[]{2}), 0));
        w.close();

        Path dir = tmp.resolve("db");
        assertTrue(Files.exists(dir.resolve("db.orders.000000000.csv")));
        assertTrue(Files.exists(dir.resolve("db.customers.000000000.csv")));
    }

    @Test
    void emptyBatch_writesNoRows() throws Exception {
        CsvDumpWriter w = new CsvDumpWriter(Long.MAX_VALUE);
        w.writeBatch(tmp, "db", "dbo", "t",
                List.of("id"), new RowBatch(List.of(), 0));
        w.close();

        // File may be created (or not), but must contain 0 data rows
        Path file = tmp.resolve("db").resolve("db.t.000000000.csv");
        if (Files.exists(file)) {
            assertEquals(0, Files.readAllLines(file, StandardCharsets.UTF_8)
                    .stream().filter(l -> !l.isEmpty()).count());
        }
    }
}
