package com.tool;

import com.tool.compare.*;
import com.tool.config.AppConfig;
import com.tool.dump.extractor.SqlServerDumpExtractor;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.schema.converter.SqlServerTypeMapper;
import com.tool.schema.verifier.SqlServerSchemaVerifier;
import com.tool.source.SqlServerDriver;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class DumpIntegrationTest {

    private static final String TEST_DB = "dump_test_db";
    private static final String OUTPUT_DIR = "tmp/dump-integration-test";
    private static final String SS_HOST = "127.0.0.1";
    private static final int SS_PORT = 1433;
    private static final String SS_USER = "sa";
    private static final String SS_PASS = "test@123";

    private static final String TIDB_HOST = "127.0.0.1";
    private static final int TIDB_PORT = 4000;
    private static final String TIDB_USER = "root";
    private static final String TIDB_PASS = "";

    private static AppConfig config;
    private static SqlServerDriver driver;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // Build AppConfig
        AppConfig.DbConfig sourceDb = new AppConfig.DbConfig();
        sourceDb.setHost(SS_HOST);
        sourceDb.setPort(SS_PORT);
        sourceDb.setUsername(SS_USER);
        sourceDb.setPassword(SS_PASS);
        config = new AppConfig();
        config.setSource(sourceDb);

        // Wire dependencies
        SqlServerExtractor extractor = new SqlServerExtractor();
        SqlServerDumpExtractor dumpExtractor = new SqlServerDumpExtractor(extractor);
        SqlServerTypeMapper typeMapper = new SqlServerTypeMapper();
        SqlServerSchemaVerifier verifier = new SqlServerSchemaVerifier(typeMapper);
        driver = new SqlServerDriver(extractor, dumpExtractor, typeMapper, verifier, config);

        // Create test database on SQL Server
        String ssUrl = "jdbc:sqlserver://" + SS_HOST + ":" + SS_PORT + ";encrypt=false";
        try (Connection c = DriverManager.getConnection(ssUrl, SS_USER, SS_PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + TEST_DB);
        }

        // Create tables and insert test data
        try (Connection c = DriverManager.getConnection(
                ssUrl + ";database=" + TEST_DB, SS_USER, SS_PASS);
             Statement s = c.createStatement()) {

            // dump_test_types — type coverage table
            s.execute("""
                CREATE TABLE dump_test_types (
                    id INT PRIMARY KEY,
                    col_bigint BIGINT,
                    col_decimal DECIMAL(18,4),
                    col_float FLOAT(53),
                    col_money MONEY,
                    col_bit BIT,
                    col_date DATE,
                    col_time TIME(0),
                    col_datetime DATETIME,
                    col_datetime2_0 DATETIME2(0),
                    col_datetime2_7 DATETIME2(7),
                    col_datetimeoffset DATETIMEOFFSET(3),
                    col_char CHAR(10),
                    col_varchar VARCHAR(50),
                    col_nvarchar NVARCHAR(50),
                    col_text TEXT,
                    col_null_str VARCHAR(20) NULL,
                    col_guid UNIQUEIDENTIFIER
                )""");

            // Normal values
            s.execute("INSERT INTO dump_test_types VALUES (" +
                    "1, 9223372036854775807, 12345.6789, 3.14159265358979, $9999.99, 1, " +
                    "'2026-01-15', '14:30:00', '2026-01-15 14:30:00', '2026-01-15 14:30:00', " +
                    "'2026-01-15 14:30:00.1234567', '2026-01-15 14:30:00.123 +05:30', " +
                    "'hello     ', 'normal varchar', N'中文Unicode', 'long text value', " +
                    "'not null', NEWID())");

            // NULL value
            s.execute("INSERT INTO dump_test_types (id, col_varchar, col_null_str) VALUES (2, 'has null', NULL)");

            // Empty string
            s.execute("INSERT INTO dump_test_types (id, col_varchar, col_char, col_null_str) " +
                    "VALUES (3, '', '', '')");

            // Special chars: quotes and commas
            s.execute("INSERT INTO dump_test_types (id, col_varchar, col_nvarchar, col_text) " +
                    "VALUES (4, 'say \"hello\"', N'含,逗号', 'line1\r\nline2')");

            // Default values
            s.execute("INSERT INTO dump_test_types (id, col_varchar) VALUES (5, 'defaults')");

            // dump_test_simple — scale table, 1000 rows
            s.execute("CREATE TABLE dump_test_simple (id INT PRIMARY KEY, val DECIMAL(10,2), name VARCHAR(100))");
            for (int i = 1; i <= 1000; i++) {
                s.execute("INSERT INTO dump_test_simple VALUES (" +
                        i + ", " + (i * 1.5) + ", 'row_" + i + "')");
            }
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        // Drop SQL Server test database
        String ssUrl = "jdbc:sqlserver://" + SS_HOST + ":" + SS_PORT + ";encrypt=false";
        try (Connection c = DriverManager.getConnection(ssUrl, SS_USER, SS_PASS);
             Statement s = c.createStatement()) {
            s.execute("ALTER DATABASE " + TEST_DB + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            s.execute("DROP DATABASE " + TEST_DB);
        } catch (Exception ignored) {}

        // Drop TiDB test database
        String tidbUrl = "jdbc:mysql://" + TIDB_HOST + ":" + TIDB_PORT + "?rewriteBatchedStatements=true";
        try (Connection c = DriverManager.getConnection(tidbUrl, TIDB_USER, TIDB_PASS);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + TEST_DB);
        } catch (Exception ignored) {}

        // Clean output dir
        try { deleteDir(Path.of(OUTPUT_DIR)); } catch (Exception ignored) {}
    }

    @Test
    void dumpAndLoadAndCompare() throws Exception {
        // Clean output dir before dump
        deleteDir(Path.of(OUTPUT_DIR));

        // 1. Dump via API
        DumpStep dumpStep = new DumpStep(config,
                driver.schemaExtractor(),
                driver.dumpExtractor(),
                () -> new CsvDumpWriter(Long.MAX_VALUE), // no file splitting
                driver.consistencyProvider(),
                driver);

        StepContext ctx = new StepContext();
        ctx.put("databases", List.of(TEST_DB));
        ctx.put("dumpOutputDir", OUTPUT_DIR);
        ctx.put("dumpFileSizeMb", 0);      // 0 = no size limit
        ctx.put("dumpChunkSize", 200000);
        ctx.put("dumpConcurrency", 2);

        StepResult result = dumpStep.execute(ctx);
        assertThat(result.isFatal()).isFalse();

        // Verify dump output files exist
        Path dumpDir = Path.of(OUTPUT_DIR, TEST_DB);
        assertThat(Files.exists(dumpDir)).isTrue();
        assertThat(Files.list(dumpDir).count()).isGreaterThanOrEqualTo(2);
    }

    private static void deleteDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
