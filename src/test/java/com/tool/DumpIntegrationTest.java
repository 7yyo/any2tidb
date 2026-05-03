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

        // Drop TiDB database from previous run (keep after test for inspection)
        String tidbUrl = "jdbc:mysql://" + TIDB_HOST + ":" + TIDB_PORT + "?rewriteBatchedStatements=true";
        try (Connection c = DriverManager.getConnection(tidbUrl, TIDB_USER, TIDB_PASS);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + TEST_DB);
        } catch (Exception ignored) {}
        // Clean output dir from previous run
        try { deleteDir(Path.of(OUTPUT_DIR)); } catch (Exception ignored) {}

        // Create test database on SQL Server (drop first if leftover from crashed run)
        String ssUrl = "jdbc:sqlserver://" + SS_HOST + ":" + SS_PORT + ";encrypt=false";
        try (Connection c = DriverManager.getConnection(ssUrl, SS_USER, SS_PASS);
             Statement s = c.createStatement()) {
            try { s.execute("ALTER DATABASE " + TEST_DB + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE"); } catch (Exception ignored) {}
            try { s.execute("DROP DATABASE " + TEST_DB); } catch (Exception ignored) {}
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

            // --- Boundary value rows (6-9): dense, all 16 columns filled ---

            // Row 6: Minimum / negative boundaries
            s.execute("INSERT INTO dump_test_types VALUES (6, " +
                    "-9223372036854775808, -99999999999999.9999, -1.79769313486231E308, -$922337203685477.5807, 0, " +
                    "'1753-01-01', '00:00:00', '1753-01-01 00:00:00.000', '1753-01-01 00:00:00', " +
                    "'1753-01-01 00:00:00.0000000', '1753-01-01 00:00:00.000 -12:00', " +
                    "'min_char  ', 'min_varchar', N'最小', 'minimum boundary text', " +
                    "'min_null', '00000000-0000-0000-0000-000000000000')");

            // Row 7: Maximum / positive boundaries
            // Use datetime values that won't round up past 9999-12-31 23:59:59
            String varchar50 = "x".repeat(50);
            String text500 = "X".repeat(500);
            s.execute("INSERT INTO dump_test_types VALUES (7, " +
                    "9223372036854775807, 99999999999999.9999, 1.79769313486231E308, $922337203685477.5807, 1, " +
                    "'9999-12-31', '23:59:59', '9999-12-31 23:59:59.000', '9999-12-31 23:59:59', " +
                    "'9999-12-31 23:59:59.1234567', '9999-12-31 23:59:59.123 +14:00', " +
                    "'MAX_CHAR_X', '" + varchar50 + "', " +
                    "N'一二三四五六七八九十一二三四五六七八九十一二三四五', " +
                    "'" + text500 + "', 'max_null', 'FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF')");

            // Row 8: Zero / neutral / midnight
            s.execute("INSERT INTO dump_test_types VALUES (8, " +
                    "0, 0.0000, 0.0, $0.00, 0, " +
                    "'2000-01-01', '12:00:00', '2000-01-01 12:00:00.000', '2000-01-01 12:00:00', " +
                    "'2000-01-01 12:00:00.5000000', '2000-01-01 12:00:00.500 +00:00', " +
                    "'0000000000', '0', N'0', '0', " +
                    "'', '11111111-1111-1111-1111-111111111111')");

            // Row 9: Precision + encoding stress
            // Backslashes doubled: LOAD DATA default ESCAPED BY '\' consumes single backslashes
            String text2000 = "A".repeat(2000);
            s.execute("INSERT INTO dump_test_types VALUES (9, " +
                    "1, 0.0001, 2.2250738585072014E-308, $0.0001, 1, " +
                    "'2024-02-29', '00:00:01', '2024-02-29 00:00:01.003', '2024-02-29 00:00:01', " +
                    "'2024-02-29 00:00:01.1234567', '2024-02-29 00:00:01.123 +05:30', " +
                    "'ABCDEFGHIJ', 'say \"hello\", comma, \\r\\n tab\\tback\\\\slash', " +
                    "N'中文测试字符\uD842\uDFB7\uD83C\uDF89', " +
                    "'" + text2000 + "', 'not null \uD83D\uDC4B', NEWID())");

            // dump_test_simple — scale table, 1000 rows
            s.execute("CREATE TABLE dump_test_simple (id INT PRIMARY KEY, val DECIMAL(10,2), name VARCHAR(100))");
            for (int i = 1; i <= 1000; i++) {
                s.execute("INSERT INTO dump_test_simple VALUES (" +
                        i + ", " + (i * 1.5) + ", 'row_" + i + "')");
            }
        }

        // Enable CDC on the test database (required by dump for LSN capture).
        // A table-level capture instance is also needed so that the capture
        // job populates cdc.lsn_time_mapping, which fn_cdc_get_max_lsn() reads.
        try (Connection c = DriverManager.getConnection(
                ssUrl + ";database=" + TEST_DB, SS_USER, SS_PASS);
             Statement s = c.createStatement()) {
            s.execute("EXEC sys.sp_cdc_enable_db");
            s.execute("EXEC sys.sp_cdc_enable_table @source_schema = 'dbo', "
                    + "@source_name = 'dump_test_simple', @role_name = NULL");
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
    }

    @Test
    void dumpAndLoadAndCompare() throws Exception {
        // Clean output dir before dump
        deleteDir(Path.of(OUTPUT_DIR));

        // 1. Dump via API
        DumpStep dumpStep = new DumpStep(config,
                driver.schemaExtractor(),
                driver.dumpExtractor(),
                () -> new CsvDumpWriter(Long.MAX_VALUE),
                driver.consistencyProvider(),
                driver);

        StepContext ctx = new StepContext();
        ctx.put("databases", List.of(TEST_DB));
        ctx.put("dumpOutputDir", OUTPUT_DIR);
        ctx.put("dumpFileSizeMb", 0);
        ctx.put("dumpChunkSize", 200000);
        ctx.put("dumpConcurrency", 2);

        StepResult result = dumpStep.execute(ctx);
        assertThat(result.isFatal())
                .as("dump should succeed: " + result.message())
                .isFalse();

        Path dumpDir = Path.of(OUTPUT_DIR, TEST_DB);
        assertThat(Files.exists(dumpDir)).isTrue();

        // 2. Generate LOAD DATA SQL from dump output
        StringBuilder loadSql = new StringBuilder();
        loadSql.append("SET GLOBAL local_infile = 1;\n");
        loadSql.append("DROP DATABASE IF EXISTS " + TEST_DB + ";\n");
        loadSql.append("CREATE DATABASE " + TEST_DB + ";\n");
        loadSql.append("USE " + TEST_DB + ";\n");

        // Discover all .csv files in dump dir
        List<Path> csvFiles = new ArrayList<>();
        try (var stream = Files.list(dumpDir)) {
            stream.filter(p -> p.toString().endsWith(".csv"))
                  .sorted()
                  .forEach(csvFiles::add);
        }

        // Generate CREATE TABLE statements for the test tables
        loadSql.append("""
            CREATE TABLE dump_test_types (
                id INT PRIMARY KEY,
                col_bigint BIGINT,
                col_decimal DECIMAL(18,4),
                col_float DOUBLE,
                col_money DECIMAL(19,4),
                col_bit TINYINT(1),
                col_date DATE,
                col_time TIME(0),
                col_datetime DATETIME(3),
                col_datetime2_0 DATETIME(0),
                col_datetime2_7 DATETIME(6),
                col_datetimeoffset DATETIME(3),
                col_char CHAR(10),
                col_varchar VARCHAR(50),
                col_nvarchar VARCHAR(50) CHARACTER SET utf8mb4,
                col_text LONGTEXT,
                col_null_str VARCHAR(20) NULL,
                col_guid VARCHAR(36)
            );
            """);
        loadSql.append("""
            CREATE TABLE dump_test_simple (
                id INT PRIMARY KEY,
                val DECIMAL(10,2),
                name VARCHAR(100)
            );
            """);

        // Add LOAD DATA for each CSV
        for (Path csv : csvFiles) {
            String tableName = extractTableName(csv.getFileName().toString());
            loadSql.append("LOAD DATA LOCAL INFILE '")
                   .append(csv.toAbsolutePath())
                   .append("' INTO TABLE ")
                   .append(tableName)
                   .append(" FIELDS TERMINATED BY ',' ENCLOSED BY '\"'")
                   .append(" LINES TERMINATED BY '\\r\\n';\n");
        }

        // Write load.sql
        Path loadSqlFile = Path.of(OUTPUT_DIR, "load.sql");
        Files.writeString(loadSqlFile, loadSql.toString());

        // 3. Execute mysql CLI
        ProcessBuilder pb = new ProcessBuilder(
                "mysql", "-h", TIDB_HOST, "-P", String.valueOf(TIDB_PORT),
                "-u", TIDB_USER,
                "--local-infile=1",
                "--default-character-set=utf8mb4",
                "-e", "source " + loadSqlFile.toAbsolutePath());
        Map<String, String> env = pb.environment();
        if (TIDB_PASS != null && !TIDB_PASS.isEmpty()) {
            env.put("MYSQL_PWD", TIDB_PASS);
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String procOutput = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertThat(exitCode)
                .as("mysql LOAD DATA failed:\n" + procOutput)
                .isEqualTo(0);

        // 4. Compare source vs TiDB
        String ssUrl = "jdbc:sqlserver://" + SS_HOST + ":" + SS_PORT
                + ";encrypt=false;database=" + TEST_DB;
        String tidbUrl = "jdbc:mysql://" + TIDB_HOST + ":" + TIDB_PORT
                + "/" + TEST_DB + "?rewriteBatchedStatements=true";

        try (Connection srcConn = DriverManager.getConnection(ssUrl, SS_USER, SS_PASS);
             Connection tgtConn = DriverManager.getConnection(tidbUrl, TIDB_USER, TIDB_PASS)) {

            DataComparator cmp = new JdbcDataComparator();
            ComparisonConfig cfg = new ComparisonConfig(TEST_DB, null,
                    List.of(new String[]{"dbo", "dump_test_types"},
                            new String[]{"dbo", "dump_test_simple"}),
                    5000, 50);
            ComparisonReport r = cmp.compare(srcConn, tgtConn, cfg);

            if (r.hasMismatches()) {
                for (TableComparison t : r.mismatched()) {
                    System.err.println("[MIS] " + t.fullName()
                            + " src=" + t.rowCountSrc() + " tgt=" + t.rowCountTgt());
                    for (ColumnDiff d : t.columnDiffs()) {
                        System.err.println("  " + TableComparison.formatPk(d.pkValues()));
                        for (ColumnDiff.Diff diff : d.diffs()) {
                            System.err.println("    " + diff.column()
                                    + ": src=" + diff.srcValue() + " tgt=" + diff.tgtValue());
                        }
                    }
                }
            }

            assertThat(r.hasMismatches())
                    .as("dump data mismatch: " + r.mismatchedTables() + " tables differ")
                    .isFalse();
            assertThat(r.skippedTables())
                    .as("some tables were skipped")
                    .isEqualTo(0);
        }
    }

    /** Extract table name from CSV filename like "dump_test_db.dump_test_types.000000000.csv" */
    private static String extractTableName(String filename) {
        // Format: <db>.<table>.<chunk>.csv
        String[] parts = filename.replace(".csv", "").split("\\.");
        return parts[1]; // second segment is table name
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
