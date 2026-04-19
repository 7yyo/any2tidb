package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.extractor.RowBatch;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DumpStepTest {

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

    @Test
    void emptyDatabase_noTablesListed_returnsOk() throws Exception {
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        assertFalse(r.isFatal());
        verify(dumpWriter, never()).writeBatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void singleTable_streamTableCalled() throws Exception {
        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), eq("dbo"), eq("orders"), anyBoolean()))
                .thenReturn(List.of("id", "amount"));
        when(dumpExtractor.estimateRowCount(any(), any(), any())).thenReturn(100L);

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        verify(dumpExtractor).streamTable(eq(mockConn), eq("dbo"), eq("orders"),
                anyInt(), anyBoolean(), any());
        verify(dumpWriter).close();
    }

    @Test
    void contextContainsDumpSummaries_afterExecution() throws Exception {
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of());
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        assertNotNull(ctx.get("dumpSummaries", List.class));
    }

    @Test
    void stepName_isDump() {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        assertEquals("Dump", step.name());
    }

    // ── snapshotIsolation = false (default) ──────────────────────────────────

    @Test
    void snapshotIsolationFalse_nolockUsed_noAlterDatabase() throws Exception {
        config.getDump().setSnapshotIsolation(false);
        config.getDump().setNolock(true);

        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), eq(true)))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mockConn);

        // No Statement executed on master connection for snapshot isolation
        verify(mockConn, never()).createStatement();
        // streamTable called with useNolock=true
        verify(dumpExtractor).streamTable(any(), any(), any(), anyInt(), eq(true), any());
    }

    // ── snapshotIsolation = true ──────────────────────────────────────────────

    @Test
    void snapshotIsolationTrue_alterDatabaseCalledIfNotOn() throws Exception {
        config.getDump().setSnapshotIsolation(true);

        // Master connection mock: returns snapshot state "OFF" → ALTER DATABASE expected
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

        // DB connection: SET TRANSACTION ISOLATION LEVEL, then readStartLsn
        Connection dbConn = mock(Connection.class);
        Statement isoSt = mock(Statement.class);
        Statement lsnSt = mock(Statement.class);
        ResultSet lsnRs = mock(ResultSet.class);
        when(lsnRs.next()).thenReturn(false);
        when(lsnSt.executeQuery(contains("fn_cdc_get_max_lsn"))).thenReturn(lsnRs);
        when(dbConn.createStatement())
                .thenReturn(isoSt)   // first call: SET TRANSACTION ISOLATION LEVEL SNAPSHOT
                .thenReturn(lsnSt);  // second call: readStartLsn

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> "master".equals(dbName) ? masterConn : dbConn);

        // ALTER DATABASE must have been called
        verify(alterSt).execute(contains("ALTER DATABASE"));
        // SET TRANSACTION ISOLATION LEVEL SNAPSHOT on the db connection
        verify(isoSt).execute(eq("SET TRANSACTION ISOLATION LEVEL SNAPSHOT"));
    }

    @Test
    void snapshotIsolationTrue_noAlterIfAlreadyOn() throws Exception {
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
        ResultSet lsnRs = mock(ResultSet.class);
        when(lsnRs.next()).thenReturn(false);
        when(lsnSt.executeQuery(contains("fn_cdc_get_max_lsn"))).thenReturn(lsnRs);
        when(dbConn.createStatement())
                .thenReturn(isoSt)
                .thenReturn(lsnSt);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> "master".equals(dbName) ? masterConn : dbConn);

        // ALTER DATABASE must NOT have been called (already ON)
        verify(masterConn, never()).createStatement();
    }

    @Test
    void snapshotIsolationTrue_streamTableCalledWithNolockFalse() throws Exception {
        config.getDump().setSnapshotIsolation(true);

        Connection masterConn = mock(Connection.class);
        PreparedStatement checkPs = mock(PreparedStatement.class);
        ResultSet checkRs = mock(ResultSet.class);
        when(masterConn.prepareStatement(any())).thenReturn(checkPs);
        when(checkPs.executeQuery()).thenReturn(checkRs);
        when(checkRs.next()).thenReturn(true).thenReturn(false);
        when(checkRs.getString(1)).thenReturn("ON");

        Connection dbConn = mock(Connection.class);
        Statement isoSt = mock(Statement.class);
        Statement lsnSt = mock(Statement.class);
        ResultSet lsnRs = mock(ResultSet.class);
        when(lsnRs.next()).thenReturn(false);
        when(lsnSt.executeQuery(any())).thenReturn(lsnRs);
        when(dbConn.createStatement())
                .thenReturn(isoSt)
                .thenReturn(lsnSt);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), any(), any(), eq(false)))
                .thenReturn(List.of("id"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> "master".equals(dbName) ? masterConn : dbConn);

        // useNolock=false when snapshotIsolation=true
        verify(dumpExtractor).streamTable(any(), any(), any(), anyInt(), eq(false), any());
    }

    // ── writeDumpMeta ─────────────────────────────────────────────────────────

    @Test
    void writeDumpMeta_createsJsonFile() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("meta-test");
        step.writeDumpMeta(outDir, Map.of("db1", "0xABCD", "db2", "0x1234"),
                "2026-04-19T00:00:00Z", List.of("db1", "db2"), true);

        Path metaFile = outDir.resolve("dump-meta.json");
        assertTrue(Files.exists(metaFile), "dump-meta.json should be created");
        String content = Files.readString(metaFile);
        assertTrue(content.contains("\"startLsnByDb\""));
        assertTrue(content.contains("\"0xABCD\""));
        assertTrue(content.contains("\"startTime\": \"2026-04-19T00:00:00Z\""));
        assertTrue(content.contains("\"db1\""));
        assertTrue(content.contains("\"db2\""));
        assertTrue(content.contains("\"snapshotIsolation\": true"));
    }

    @Test
    void writeDumpMeta_nullLsn_writesJsonNull() throws Exception {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        Path outDir = tmp.resolve("meta-null");
        Map<String, String> lsnByDb = new java.util.LinkedHashMap<>();
        lsnByDb.put("db1", null);
        step.writeDumpMeta(outDir, lsnByDb, "2026-04-19T00:00:00Z", List.of("db1"), false);

        String content = Files.readString(outDir.resolve("dump-meta.json"));
        assertTrue(content.contains("\"db1\": null"));
        assertTrue(content.contains("\"snapshotIsolation\": false"));
    }

    @Test
    void writeDumpMeta_writtenAfterExecution() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("mydb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        // Find any dump-meta.json under tmp (outputRoot includes timestamp subdir)
        boolean found = Files.walk(tmp)
                .anyMatch(p -> p.getFileName().toString().equals("dump-meta.json"));
        assertTrue(found, "dump-meta.json should be written after execution");
    }

    @Test
    void writeDumpMeta_writtenEvenOnException() throws Exception {
        config.getDump().setSnapshotIsolation(false);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("mydb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenThrow(new RuntimeException("simulated export failure"));

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);

        assertThrows(Exception.class,
                () -> step.executeWithConnections(ctx, dbName -> mock(Connection.class)));

        boolean found = Files.walk(tmp)
                .anyMatch(p -> p.getFileName().toString().equals("dump-meta.json"));
        assertTrue(found, "dump-meta.json should be written even when the loop throws");
    }

    @Test
    void snapshotIsolationTrue_lsnRecordedPerDb() throws Exception {
        config.getDump().setSnapshotIsolation(true);

        // Master connection: snapshot already ON, no ALTER needed
        Connection masterConn = mock(Connection.class);
        PreparedStatement checkPs = mock(PreparedStatement.class);
        ResultSet checkRs = mock(ResultSet.class);
        when(masterConn.prepareStatement(contains("snapshot_isolation_state_desc")))
                .thenReturn(checkPs);
        when(checkPs.executeQuery()).thenReturn(checkRs);
        when(checkRs.next()).thenReturn(true);
        when(checkRs.getString(1)).thenReturn("ON");

        // DB-scoped connection: readStartLsn returns a value
        Connection dbConn = mock(Connection.class);
        Statement isoSt = mock(Statement.class);
        Statement lsnSt = mock(Statement.class);
        ResultSet lsnRs = mock(ResultSet.class);
        byte[] lsnBytes = new byte[]{0x00, 0x00, 0x00, 0x2A};
        when(lsnRs.next()).thenReturn(true);
        when(lsnRs.getBytes(1)).thenReturn(lsnBytes);
        when(lsnSt.executeQuery(contains("fn_cdc_get_max_lsn"))).thenReturn(lsnRs);
        // createStatement: first call for SET TRANSACTION ISOLATION LEVEL, second for readStartLsn
        when(dbConn.createStatement())
                .thenReturn(isoSt)
                .thenReturn(lsnSt);

        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("mydb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> "master".equals(dbName) ? masterConn : dbConn);

        // readStartLsn must have been called on dbConn (not masterConn)
        verify(lsnSt).executeQuery(contains("fn_cdc_get_max_lsn"));
        // masterConn should NOT have had fn_cdc_get_max_lsn called on it
        verify(masterConn, never()).createStatement();

        // dump-meta.json should contain startLsnByDb with mydb key
        boolean found = Files.walk(tmp)
                .filter(p -> p.getFileName().toString().equals("dump-meta.json"))
                .findFirst()
                .map(p -> {
                    try {
                        String c = Files.readString(p);
                        return c.contains("\"startLsnByDb\"") && c.contains("\"mydb\"");
                    } catch (Exception e) { return false; }
                })
                .orElse(false);
        assertTrue(found, "dump-meta.json should contain startLsnByDb with mydb entry");
    }
}
