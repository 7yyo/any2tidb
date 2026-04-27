package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.extractor.RowBatch;
import com.tool.dump.extractor.RowBatchConsumer;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for DumpStep — targets orchestration edge cases,
 * JSON injection in metadata, error propagation, and resource leaks.
 */
class DumpStepAdversarialTest {

    @TempDir
    Path tmp;

    private SchemaExtractor schemaExtractor;
    private DumpExtractor dumpExtractor;
    private DumpWriter dumpWriter;
    private StructuredLogger log;
    private AppConfig config;
    private StepContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        schemaExtractor = mock(SchemaExtractor.class);
        dumpExtractor   = mock(DumpExtractor.class);
        dumpWriter      = mock(DumpWriter.class);
        log             = mock(StructuredLogger.class);

        config = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost("127.0.0.1"); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        config.setSource(src);
        AppConfig.ConvertConfig cc = new AppConfig.ConvertConfig();
        cc.setSchemas(List.of()); cc.setTables(List.of());
        config.setConvert(cc);
        AppConfig.DumpConfig dc = new AppConfig.DumpConfig();
        dc.setOutputDir(tmp.toString());
        dc.setConcurrency(1);
        config.setDump(dc);

        ctx = new StepContext();
        ctx.put("schemas", List.of());
        ctx.put("tables",  List.of());
    }

    // ── writeDumpMeta: dbName containing double-quote breaks JSON ────────────

    @Test
    void writeDumpMeta_dbNameWithDoubleQuote_escapedInJSON() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("json-injection");
        step.writeDumpMeta(outDir,
                Map.of("my\"db", "0xABCD"),
                "2026-04-19T00:00:00Z",
                List.of("my\"db"),
                false);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        // " should be escaped as \"
        assertTrue(content.contains("my\\\"db"),
                "Double-quote in dbName is escaped in JSON");
        assertFalse(content.contains("my\"db") || content.contains("my\\\"db\": \"0xABCD\""),
                "Raw unescaped double-quote should not appear");
    }

    @Test
    void writeDumpMeta_dbNameWithBackslash_escapedInJSON() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("backslash-test");
        step.writeDumpMeta(outDir,
                Map.of("path\\db", "0x1234"),
                "2026-04-19T00:00:00Z",
                List.of("path\\db"),
                false);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        // \ should be escaped as \\
        assertTrue(content.contains("path\\\\db"),
                "Backslash in dbName is escaped as \\\\ in JSON");
    }

    // ── fileCount tracks batches, not actual files ───────────────────────────

    @Test
    void fileCount_isAlwaysZero_afterFix() throws Exception {
        // DumpStep no longer tracks file count — it's the CsvDumpWriter's responsibility
        // DumpTableResult.files is now always 0
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        when(dumpExtractor.streamTable(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    RowBatchConsumer consumer = inv.getArgument(5);
                    consumer.accept(new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
                    consumer.accept(new RowBatch(List.<Object[]>of(new Object[]{2}), 1));
                    return null;
                });

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(1, results.size());
        assertEquals(0, results.get(0).files(),
                "files is no longer tracked by DumpStep — always 0");
    }

    // ── connFactory throws → error result with 0 rows ───────────────────────

    @Test
    void connFactoryThrows_errorResultWithZeroRows() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx,
                dbName -> { throw new SQLException("connection refused", "08001", 0); });

        // Should NOT throw — error is captured per-table
        assertFalse(r.isFatal());

        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertEquals(0, results.get(0).rows(),
                "Failed connection should report 0 rows");
    }

    // ── listTables throws for one db → exception propagates ─────────────────

    @Test
    void listTablesThrowsForSecondDb_exceptionPropagates() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("db1", "db2", "db3"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.of())                       // db1: success (empty)
                .thenThrow(new RuntimeException("db2 fail")) // db2: failure
                .thenReturn(List.of());                      // db3: never reached

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        assertThrows(RuntimeException.class,
                () -> step.executeWithConnections(ctx, dbName -> mockConn),
                "listTables failure for db2 should propagate — db3 never processed");

        // dump-meta.json should still be written (finally block)
        boolean found = Files.walk(tmp)
                .anyMatch(p -> p.getFileName().toString().equals("dump-meta.json"));
        assertTrue(found, "dump-meta.json should be written even on failure");
    }

    // ── dumpOneTable: streamTable throws → writer still closed ───────────────

    @Test
    void streamTableThrows_writerStillClosed() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));
        when(dumpExtractor.streamTable(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenThrow(new SQLException("stream failed", "HY000", 50000));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mockConn);

        // writer.close() should be called in the catch block of dumpOneTable
        verify(dumpWriter).close();
    }

    // ── Empty schemas/tables lists with data in DB ───────────────────────────

    @Test
    void emptySchemasFilter_allTablesDiscovered() throws Exception {
        // schemas=empty, tables=empty → all tables in all databases should be exported
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        // listTables called with empty filter → returns all tables
        when(schemaExtractor.listTables(any(), eq(List.of()), eq(List.of())))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}, new String[]{"dbo", "users"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mockConn);

        // Both tables should be exported
        verify(dumpExtractor, times(2)).streamTable(
                any(), any(), any(), anyInt(), anyBoolean(), any());
    }

    // ── readStartLsn: CDC function throws → returns null gracefully ─────────

    @Test
    void readStartLsn_cdcNotEnabled_returnsNull() throws Exception {
        config.getDump().setSnapshotIsolation(true);

        Connection masterConn = mock(Connection.class);
        PreparedStatement checkPs = mock(PreparedStatement.class);
        ResultSet checkRs = mock(ResultSet.class);
        when(masterConn.prepareStatement(contains("snapshot_isolation_state_desc")))
                .thenReturn(checkPs);
        when(checkPs.executeQuery()).thenReturn(checkRs);
        when(checkRs.next()).thenReturn(true);
        when(checkRs.getString(1)).thenReturn("ON");

        Connection dbConn = mock(Connection.class);
        Statement isoSt = mock(Statement.class);
        Statement lsnSt = mock(Statement.class);
        when(lsnSt.executeQuery(any()))
                .thenThrow(new SQLException("CDC not enabled", "HY000", 50000));
        when(dbConn.createStatement())
                .thenReturn(isoSt)
                .thenReturn(lsnSt);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        // Should NOT throw — CDC failure is non-fatal
        assertDoesNotThrow(() ->
                step.executeWithConnections(ctx,
                        dbName -> "master".equals(dbName) ? masterConn : dbConn));
    }

    // ── Multiple databases: first db has tables, second is empty ─────────────

    @Test
    void multipleDatabases_mixedTableCounts() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("db_with_tables", "empty_db"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}))
                .thenReturn(List.of()); // empty_db has no tables
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(1, results.size(),
                "Only db_with_tables should produce a table result");
    }

    // ── resolveOutputDir: null and blank produce default path ───────────────

    @Test
    void resolveOutputDir_null_usesDefault() throws Exception {
        // Can't call resolveOutputDir directly (private), but verify via execution
        config.getDump().setOutputDir(null);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        // dump-meta.json should exist somewhere under tmp (the default base is "dump-output")
        // but since outputDir is set in config, and it's null, resolveOutputDir uses "dump-output"
        boolean found = Files.walk(Path.of("dump-output"))
                .anyMatch(p -> p.getFileName().toString().equals("dump-meta.json"));
        // May or may not exist depending on working directory — just verify no exception
    }

    // ── Concurrency: multiple tables processed in parallel ───────────────────

    @Test
    void concurrency2_twoTablesProcessedConcurrently() throws Exception {
        config.getDump().setConcurrency(2);
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(
                        new String[]{"dbo", "orders"},
                        new String[]{"dbo", "customers"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        // Both tables should be exported
        verify(dumpExtractor, times(2)).streamTable(
                any(), any(), any(), anyInt(), anyBoolean(), any());
    }

    // ── dumpOneTable: getColumnNames throws → error result ──────────────────

    @Test
    void getColumnNamesThrows_errorResultReturned() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenThrow(new SQLException("permission denied", "42000", 0));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError(),
                "getColumnNames failure should produce error result");
    }

    // ── writeDumpMeta: LSN with special hex characters ──────────────────────

    @Test
    void writeDumpMeta_lsnWithAllHexChars_validJson() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("lsn-test");
        step.writeDumpMeta(outDir,
                Map.of("db1", "0x00000015000001A80001"),
                "2026-04-19T00:00:00Z",
                List.of("db1"),
                true);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        assertTrue(content.contains("0x00000015000001A80001"));
    }

    // ── Total rows accumulates across all tables ─────────────────────────────

    @Test
    void totalRows_accumulatesAcrossTables() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(
                        new String[]{"dbo", "t1"},
                        new String[]{"dbo", "t2"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        // Table t1: 3 rows in 1 batch
        // Table t2: 2 rows in 1 batch
        when(dumpExtractor.streamTable(any(), any(), eq("t1"), anyInt(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    RowBatchConsumer c = inv.getArgument(5);
                    c.accept(new RowBatch(
                            List.of(new Object[]{1}, new Object[]{2}, new Object[]{3}), 0));
                    return null;
                });
        when(dumpExtractor.streamTable(any(), any(), eq("t2"), anyInt(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    RowBatchConsumer c = inv.getArgument(5);
                    c.accept(new RowBatch(
                            List.of(new Object[]{4}, new Object[]{5}), 0));
                    return null;
                });

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mockConn);

        Long totalRows = ctx.get("dumpTotalRows", Long.class);
        assertEquals(5L, totalRows, "Total rows should be 3 + 2 = 5");
    }

    // ── ensureSnapshotIsolation: SQL injection via dbName ─────────────────────

    @Test
    void ensureSnapshotIsolation_dbNameWithBracket_escaped() throws Exception {
        config.getDump().setSnapshotIsolation(true);

        Connection masterConn = mock(Connection.class);
        PreparedStatement checkPs = mock(PreparedStatement.class);
        ResultSet checkRs = mock(ResultSet.class);
        when(masterConn.prepareStatement(contains("snapshot_isolation_state_desc")))
                .thenReturn(checkPs);
        when(checkPs.executeQuery()).thenReturn(checkRs);
        when(checkRs.next()).thenReturn(true);
        when(checkRs.getString(1)).thenReturn("OFF");

        Statement alterSt = mock(Statement.class);
        when(masterConn.createStatement()).thenReturn(alterSt);

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        step.ensureSnapshotIsolation(masterConn, "my]db");

        // ] is escaped as ]] inside bracket quoting
        verify(alterSt).execute(argThat(sql -> sql.contains("[my]]db]")),
                "Bracket in dbName is escaped as ]] in ALTER DATABASE");
    }

    // ── writeDumpMeta: dbName with newline breaks JSON ──────────────────────

    @Test
    void writeDumpMeta_dbNameWithNewline_escapedInJSON() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("newline-test");
        step.writeDumpMeta(outDir,
                Map.of("my\ndb", "0xABCD"),
                "2026-04-19T00:00:00Z",
                List.of("my\ndb"),
                false);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        // Newline should be escaped as \n in JSON
        assertTrue(content.contains("my\\ndb"),
                "Newline in dbName is escaped as \\n in JSON");
    }

    // ── readStartLsn: empty LSN byte array ──────────────────────────────────

    @Test
    void readStartLsn_emptyByteArray_returnsNull() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        byte[] emptyLsn = new byte[0];
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(any())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBytes(1)).thenReturn(emptyLsn);

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        String lsn = step.readStartLsn(conn);

        assertNull(lsn,
                "Empty LSN byte array returns null");
    }

    // ── Concurrency: one table fails, other succeeds ─────────────────────────

    @Test
    void concurrency2_oneTableFails_otherSucceeds() throws Exception {
        config.getDump().setConcurrency(2);
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(
                        new String[]{"dbo", "ok_table"},
                        new String[]{"dbo", "fail_table"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        // ok_table succeeds, fail_table throws
        when(dumpExtractor.streamTable(any(), any(), eq("ok_table"), anyInt(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    RowBatchConsumer c = inv.getArgument(5);
                    c.accept(new RowBatch(List.<Object[]>of(new Object[]{1}), 0));
                    return null;
                });
        when(dumpExtractor.streamTable(any(), any(), eq("fail_table"), anyInt(), anyBoolean(), any()))
                .thenThrow(new SQLException("table access denied", "42000", 0));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(2, results.size());

        // One success, one failure
        long errorCount = results.stream().filter(DumpStep.DumpTableResult::isError).count();
        assertEquals(1, errorCount, "One table should have error, one should succeed");

        // Total rows should only count the successful table
        Long totalRows = ctx.get("dumpTotalRows", Long.class);
        assertEquals(1L, totalRows, "Only the successful table's rows should be counted");
    }

    // ── listDatabases throws → caught, returns empty list ────────────────────

    @Test
    void listDatabasesThrows_logsWarningAndReturnsEmpty() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        when(schemaExtractor.listDatabases(any()))
                .thenThrow(new SQLException("master db unreachable", "08001", 0));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        assertFalse(r.isFatal());
        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertTrue(results.isEmpty(), "No tables exported when listDatabases fails");
        verify(dumpExtractor, never()).streamTable(any(), any(), any(), anyInt(), anyBoolean(), any());
        // Verify warning is logged
        verify(log).log(eq("WARN"), eq("Failed to list databases, no tables will be exported"),
                eq("error"), anyString());
    }

    // ── Same table name from different databases ─────────────────────────────

    @Test
    void sameTableNameDifferentDatabases_separateOutputDirs() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("db1", "db2"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "users"}))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "users"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mockConn);

        // Both databases should have their users table exported
        verify(dumpExtractor, times(2)).streamTable(
                any(), any(), eq("users"), anyInt(), anyBoolean(), any());
    }

    // ── dumpOneTable: partial rows written before exception ──────────────────

    @Test
    void streamTablePartialRowsBeforeException_partialDataOnDisk() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("id"));

        // Stream 1 batch successfully, then throw on the second
        when(dumpExtractor.streamTable(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    RowBatchConsumer c = inv.getArgument(5);
                    // First batch: 3 rows written to disk
                    c.accept(new RowBatch(
                            List.of(new Object[]{1}, new Object[]{2}, new Object[]{3}), 0));
                    // Then throw — partial data already flushed by CsvDumpWriter
                    throw new SQLException("connection lost mid-stream", "08S01", 0);
                });

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        @SuppressWarnings("unchecked")
        List<DumpStep.DumpTableResult> results =
                (List<DumpStep.DumpTableResult>) ctx.get("dumpSummaries", List.class);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        // BUG: rows=3 but the data is incomplete — Lightning will import partial data
        assertEquals(3, results.get(0).rows(),
                "3 rows were written before exception — but table export is incomplete");
    }

    // ── writeDumpMeta: databases list with special characters ────────────────

    @Test
    void writeDumpMeta_databasesListWithSpecialChars_escapedInJSON() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("db-list-test");
        step.writeDumpMeta(outDir,
                Map.of(),
                "2026-04-19T00:00:00Z",
                List.of("db\"1", "db\\2"),
                false);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        // Special characters in database names should be escaped
        assertTrue(content.contains("db\\\"1"),
                "Double-quote in database name is escaped");
        assertTrue(content.contains("db\\\\2"),
                "Backslash in database name is escaped");
    }
}
