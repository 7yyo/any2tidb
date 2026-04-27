package com.tool.pipeline.steps;

import com.tool.common.model.ConversionResult;
import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.pipeline.StepContext;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.writer.SchemaWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for SchemaMigrateStep — targets SQL injection in openTiDB,
 * table name conflict detection, dry-run edge cases, and error accumulation.
 */
class SchemaMigrateStepAdversarialTest {

    @TempDir
    Path tmp;

    private SchemaMigrateStep step;
    private SchemaExtractor extractor;
    private SchemaWriter writer;
    private StructuredLogger log;
    private ProgressReporter progress;
    private AppConfig config;
    private StepContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        extractor = mock(SchemaExtractor.class);
        writer = mock(SchemaWriter.class);
        log = mock(StructuredLogger.class);
        progress = mock(ProgressReporter.class);

        config = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost("127.0.0.1"); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        config.setSource(src);
        AppConfig.DbConfig tgt = new AppConfig.DbConfig();
        tgt.setHost("127.0.0.1"); tgt.setPort(4000);
        tgt.setUsername("root"); tgt.setPassword("");
        config.setTarget(tgt);

        ctx = new StepContext();
        ctx.put("schemas", List.of());
        ctx.put("tables", List.of());
    }

    // ── openTiDB: SQL injection via dbName ─────────────────────────────────

    @Test
    void openTiDB_dbNameWithQuote_escaped() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        // Set up extractor to return empty table list
        when(extractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(extractor.listTables(any(), any(), any())).thenReturn(List.of());
        ctx.put("dryRun", true);

        SchemaMigrateStep testStep = createStep("testdb; DROP DATABASE x--");
        // Backtick in dbName is now escaped: `testdb``; DROP DATABASE x--`

        testStep.execute(ctx);

        verify(stmt).execute(argThat(sql ->
                sql.contains("CREATE DATABASE") && sql.contains("testdb``; DROP DATABASE x--")));
    }

    // ── Table name conflict across schemas ──────────────────────────────────────

    @Test
    void migrateOneDb_tableNameConflict_stopsEarly() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listTables(any(), any(), any()))
                .thenReturn(List.of(
                        new String[]{"schema1", "orders"},
                        new String[]{"schema2", "orders"}));

        SchemaMigrateStep testStep = createStep("testdb");
        StepResult r = testStep.execute(ctx);

        assertTrue(r.isFatal());
        assertTrue(r.message().contains("conflict"));
    }

    // ── continueOnError=true accumulates errors ──────────────────────────────

    @Test
    void continueOnError_true_accumulatesErrors() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(extractor.listTables(any(), any(), any()))
                .thenReturn(List.of(new String[]{"dbo", "t1"}));
        // extractTable throws
        when(extractor.extractTable(any(), any(), any()))
                .thenThrow(new SQLException("access denied"));

        ctx.put("continueOnError", true);
        SchemaMigrateStep testStep = createStep("testdb");
        StepResult r = testStep.execute(ctx);

        assertFalse(r.isFatal(), "continueOnError=true should not produce fatal result");
        assertEquals(1, ctx.get("totalFailed", Integer.class));
    }

    @Test
    void continueOnError_false_stopsOnFirstError() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(extractor.listTables(any(), any(), any()))
                .thenReturn(List.of(new String[]{"dbo", "t1"}));
        when(extractor.extractTable(any(), any(), any()))
                .thenThrow(new SQLException("access denied"));

        ctx.put("continueOnError", false);
        SchemaMigrateStep testStep = createStep("testdb");
        StepResult r = testStep.execute(ctx);

        assertTrue(r.isFatal(), "continueOnError=false should stop early on first error");
    }

    // ── dryRun: SQL file written to current directory ────────────────────────

    @Test
    void dryRun_writesSqlFileToCurrentDir() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listDatabases(any())).thenReturn(List.of("mydb"));
        when(extractor.listTables(any(), any(), any()))
                .thenReturn(List.of());
        ctx.put("dryRun", true);

        SchemaMigrateStep testStep = createStep("mydb");
        testStep.execute(ctx);

        // SQL file should be created in current directory
        assertTrue(Files.exists(tmp.resolve("mydb.sql")));
    }

    // ── dryRun: SQL file contains USE backtick ────────────────────────────────

    @Test
    void dryRun_sqlFileContainsBackticks() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(extractor.listTables(any(), any(), any()))
                .thenReturn(List.of());
        ctx.put("dryRun", true);

        SchemaMigrateStep testStep = createStep("testdb");
        testStep.execute(ctx);

        String content = Files.readString(tmp.resolve("testdb.sql"));
        assertTrue(content.contains("USE `testdb`;"),
                "dry-run SQL file should contain USE with backticks");
    }

    // ── empty databases list ────────────────────────────────────────────────

    @Test
    void emptyDatabases_noMigration() throws Exception {
        Connection conn = mock(Connection.class);
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenReturn(conn);
        when(extractor.listDatabases(any())).thenReturn(List.of());

        SchemaMigrateStep testStep = createStep("testdb");
        StepResult r = testStep.execute(ctx);

        assertFalse(r.isFatal());
        assertEquals(0, ctx.get("totalFailed", Integer.class));
    }

    // ── listDatabases throws ────────────────────────────────────────────────

    @Test
    void listDatabasesThrows_propagates() throws Exception {
        when(DriverManager.getConnection(anyString(), anyString(), anyString()))
                .thenThrow(new SQLException("connection failed", "08001", 0));

        SchemaMigrateStep testStep = createStep("testdb");
        assertThrows(SQLException.class, () -> testStep.execute(ctx));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SchemaMigrateStep createStep(String dbName) {
        return new SchemaMigrateStep(config, extractor,
                new com.tool.schema.converter.SchemaConverter(),
                writer, log, progress);
    }
}
