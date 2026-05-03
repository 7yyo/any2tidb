# Dump Integration Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integration test that end-to-end verifies dump correctness: dump CSV via API → LOAD DATA to TiDB → DataComparator assert.

**Architecture:** Single JUnit 5 test class (`DumpIntegrationTest`) tagged `@Tag("integration")`, wired with real SQL Server + TiDB connections. Uses DumpStep API for dump, mysql CLI for load, DataComparator for verification.

**Tech Stack:** JUnit 5, DataComparator, DumpStep, `Runtime.exec` mysql CLI, AssertJ

---

## File Map

| File | Role |
|---|---|
| `src/test/java/com/tool/DumpIntegrationTest.java` | Integration test (NEW) |
| `pom.xml` | Exclude integration tag from `mvn test` (MODIFY) |

---

### Task 1: Test scaffolding — setup and teardown

**Files:**
- Create: `src/test/java/com/tool/DumpIntegrationTest.java`

- [ ] **Step 1: Write the test skeleton with setup and teardown**

```java
package com.tool;

import com.tool.compare.*;
import com.tool.config.AppConfig;
import com.tool.dump.extractor.SqlServerDumpExtractor;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.source.SqlServerConsistencyProvider;
import com.tool.source.SqlServerDriver;
import com.tool.schema.converter.SqlServerTypeMapper;
import com.tool.schema.verifier.SqlServerSchemaVerifier;
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

    private static void deleteDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
```

- [ ] **Step 2: Compile to verify dependencies are satisfied**

Run: `mvn compile test-compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/DumpIntegrationTest.java
git commit -m "test: add DumpIntegrationTest scaffolding with setup and teardown"
```

---

### Task 2: Dump invocation via DumpStep API

**Files:**
- Modify: `src/test/java/com/tool/DumpIntegrationTest.java`

- [ ] **Step 1: Add the dump test method**

Append to `DumpIntegrationTest`:

```java
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
```

- [ ] **Step 2: Run only this test with @Tag("integration")**

Run: `mvn test -Dgroups="integration" -Dtest=DumpIntegrationTest -DfailIfNoTests=false 2>&1 | tail -15`
Expected: dump succeeds, CSV files created

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/DumpIntegrationTest.java
git commit -m "test: add dump invocation via DumpStep API"
```

---

### Task 3: LOAD DATA to TiDB and comparison

**Files:**
- Modify: `src/test/java/com/tool/DumpIntegrationTest.java`

- [ ] **Step 1: Extend test with LOAD DATA + comparison**

Replace the test method with complete flow:

```java
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

        // For each CSV file, we need the corresponding CREATE TABLE from schema
        // The dump step writes schema SQL to the output dir. Find it.
        Path schemaDir = Path.of(OUTPUT_DIR, TEST_DB);
        // Schema files are produced by SchemaMigrateStep. For dump-only mode,
        // we need to generate CREATE TABLE statements ourselves for the test tables.
        // We know the exact table schemas, so include them directly:
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
                col_datetime DATETIME,
                col_datetime2_0 DATETIME,
                col_datetime2_7 DATETIME(6),
                col_datetimeoffset DATETIME(3),
                col_char CHAR(10),
                col_varchar VARCHAR(50),
                col_nvarchar VARCHAR(25) CHARACTER SET utf8mb4,
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
            ComparisonReport r = cmp.compare(srcConn, tgtConn,
                    ComparisonConfig.defaults(TEST_DB));

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
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile test-compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/DumpIntegrationTest.java
git commit -m "test: add LOAD DATA + DataComparator verification to integration test"
```

---

### Task 4: Exclude @Tag("integration") from default test suite

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Configure surefire to skip integration tests by default**

```xml
<!-- Inside <build><plugins>, modify maven-surefire-plugin -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludedGroups>integration</excludedGroups>
    </configuration>
</plugin>
```

- [ ] **Step 2: Verify unit tests still pass (integration test excluded)**

Run: `mvn test 2>&1 | tail -10`
Expected: BUILD SUCCESS, 28 tests pass (DumpIntegrationTest excluded)

- [ ] **Step 3: Run integration test explicitly**

Run: `mvn test -Dgroups="integration" -Dtest=DumpIntegrationTest -DfailIfNoTests=false 2>&1 | tail -20`
Expected: test executes (may fail if SQL Server/TiDB not available — that's expected for CI)

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "test: exclude @Tag(\"integration\") from default mvn test"
```

---

### Task 5: Final verification

- [ ] **Step 1: Clean build + all unit tests**

```bash
mvn clean test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, all unit tests pass, integration test excluded

- [ ] **Step 2: Publish JAR**

```bash
mvn package -q -DskipTests && cp target/any2tidb-1.0.0.jar dist/
```
