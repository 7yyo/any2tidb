# DataComparator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a reusable JDBC-based `DataComparator` interface with table-level, PK-aligned row comparison.

**Architecture:** 5 model records + 1 interface + 1 JDBC implementation. Models are immutable data carriers. `JdbcDataComparator` implements the compare flow: table discovery → PK discovery → COUNT(*) → streaming PK-ordered comparison with value normalization.

**Tech Stack:** JDBC metadata API, Java records, JUnit 5 + AssertJ (from spring-boot-starter-test), H2 in-memory for unit tests

---

## File Map

| File | Role |
|---|---|
| `src/main/java/com/tool/compare/ComparisonConfig.java` | Configuration record (NEW) |
| `src/main/java/com/tool/compare/ComparisonReport.java` | Top-level report with summary + table list (NEW) |
| `src/main/java/com/tool/compare/TableComparison.java` | Per-table result: counts, missing, extra, diffs (NEW) |
| `src/main/java/com/tool/compare/ColumnDiff.java` | Single row's column diffs: pk + {col, srcVal, tgtVal} (NEW) |
| `src/main/java/com/tool/compare/DataComparator.java` | Interface: `compare(Connection, Connection, ComparisonConfig)` (NEW) |
| `src/main/java/com/tool/compare/JdbcDataComparator.java` | JDBC implementation (NEW) |
| `src/main/java/com/tool/compare/ValueNormalizer.java` | Value normalization for comparison (NEW) |
| `src/test/java/com/tool/compare/DataComparatorTest.java` | Unit tests with H2 (NEW) |
| `src/main/java/com/tool/CompareData.java` | Rewrite to use JdbcDataComparator (MODIFY) |

---

### Task 0: Add H2 test dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add H2 dependency**

```xml
<!-- Inside <dependencies> section, add before spring-boot-starter-test -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify H2 resolves**

Run: `mvn dependency:resolve -DincludeScope=test 2>&1 | grep h2`
Expected: `com.h2database:h2:jar` listed

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add H2 test dependency for DataComparator tests"
```

---

### Task 1: ComparisonConfig record

**Files:**
- Create: `src/main/java/com/tool/compare/ComparisonConfig.java`
- Test: `src/test/java/com/tool/compare/DataComparatorTest.java`

- [ ] **Step 1: Write the failing test for config defaults**

```java
package com.tool.compare;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DataComparatorTest {

    @Test
    void configDefaultsShouldSetSensibleValues() {
        ComparisonConfig c = ComparisonConfig.defaults("mydb");
        assertThat(c.catalog()).isEqualTo("mydb");
        assertThat(c.targetCatalog()).isNull();
        assertThat(c.tables()).isEmpty();
        assertThat(c.batchSize()).isEqualTo(5000);
        assertThat(c.maxMismatchRows()).isEqualTo(50);
    }

    @Test
    void configShouldRejectNullCatalog() {
        assertThatThrownBy(() -> new ComparisonConfig(null, null, List.of(), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void configShouldRejectBlankCatalog() {
        assertThatThrownBy(() -> new ComparisonConfig("  ", null, List.of(), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void configShouldClampNegativeBatchSize() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), -1, 10);
        assertThat(c.batchSize()).isEqualTo(5000);
    }

    @Test
    void configShouldClampNegativeMaxMismatch() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), 1000, -5);
        assertThat(c.maxMismatchRows()).isEqualTo(50);
    }

    @Test
    void configShouldAcceptZeroMaxMismatch() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), 1000, 0);
        assertThat(c.maxMismatchRows()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=DataComparatorTest -DfailIfNoTests=false 2>&1 | tail -5`
Expected: compilation error — `ComparisonConfig` not defined

- [ ] **Step 3: Write ComparisonConfig**

```java
package com.tool.compare;

import java.util.List;

public record ComparisonConfig(
    String catalog,
    String targetCatalog,
    List<String[]> tables,
    int batchSize,
    int maxMismatchRows
) {
    public ComparisonConfig {
        if (catalog == null || catalog.isBlank()) throw new IllegalArgumentException("catalog required");
        if (batchSize <= 0) batchSize = 5000;
        if (maxMismatchRows < 0) maxMismatchRows = 50;
    }

    public static ComparisonConfig defaults(String catalog) {
        return new ComparisonConfig(catalog, null, List.of(), 5000, 50);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=DataComparatorTest -DfailIfNoTests=false 2>&1 | tail -5`
Expected: 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/compare/ComparisonConfig.java src/test/java/com/tool/compare/DataComparatorTest.java
git commit -m "feat: add ComparisonConfig record with validation"
```

---

### Task 2: ColumnDiff record

**Files:**
- Create: `src/main/java/com/tool/compare/ColumnDiff.java`

- [ ] **Step 1: Write ColumnDiff**

```java
package com.tool.compare;

import java.util.List;
import java.util.Map;

/** A single row's column-level differences, identified by its primary key values. */
public record ColumnDiff(
    Map<String, String> pkValues,
    List<Diff> diffs
) {
    public record Diff(String column, String srcValue, String tgtValue) {}

    public boolean hasDiff() { return !diffs.isEmpty(); }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/compare/ColumnDiff.java
git commit -m "feat: add ColumnDiff record"
```

---

### Task 3: TableComparison record

**Files:**
- Create: `src/main/java/com/tool/compare/TableComparison.java`

- [ ] **Step 1: Write TableComparison**

```java
package com.tool.compare;

import java.util.List;

public record TableComparison(
    String fullName,
    Status status,
    long rowCountSrc,
    long rowCountTgt,
    List<String> missingInTarget,
    List<String> extraInTarget,
    List<ColumnDiff> columnDiffs
) {
    public enum Status { MATCHED, MISMATCHED, SKIPPED }

    public boolean isMatched() { return status == Status.MATCHED; }

    /** Human-readable PK representation, e.g. "(id=1053)" */
    public static String formatPk(java.util.Map<String, String> pk) {
        if (pk.isEmpty()) return "(no pk)";
        StringBuilder sb = new StringBuilder("(");
        for (var e : pk.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(")");
        return sb.toString();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/compare/TableComparison.java
git commit -m "feat: add TableComparison record"
```

---

### Task 4: ComparisonReport record

**Files:**
- Create: `src/main/java/com/tool/compare/ComparisonReport.java`

- [ ] **Step 1: Write ComparisonReport**

```java
package com.tool.compare;

import java.util.List;

public record ComparisonReport(
    int totalTables,
    int matchedTables,
    int mismatchedTables,
    int skippedTables,
    long totalRowsSrc,
    long totalRowsTgt,
    List<TableComparison> tables
) {
    public boolean hasMismatches() {
        return mismatchedTables > 0;
    }

    public List<TableComparison> mismatched() {
        return tables.stream()
                .filter(t -> t.status() == TableComparison.Status.MISMATCHED)
                .toList();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/compare/ComparisonReport.java
git commit -m "feat: add ComparisonReport record"
```

---

### Task 5: DataComparator interface

**Files:**
- Create: `src/main/java/com/tool/compare/DataComparator.java`

- [ ] **Step 1: Write DataComparator**

```java
package com.tool.compare;

import java.sql.Connection;

public interface DataComparator {
    ComparisonReport compare(Connection source, Connection target, ComparisonConfig config);
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/compare/DataComparator.java
git commit -m "feat: add DataComparator interface"
```

---

### Task 6: ValueNormalizer utility

**Files:**
- Create: `src/main/java/com/tool/compare/ValueNormalizer.java`

- [ ] **Step 1: Write ValueNormalizer**

```java
package com.tool.compare;

import java.math.BigDecimal;

/** Normalizes JDBC values so source-target comparison avoids false positives. */
final class ValueNormalizer {

    private ValueNormalizer() {}

    /** Convert an object from ResultSet.getObject() to a normalized string for comparison. */
    static String normalize(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (val instanceof String s) {
            // Normalize line endings
            return s.replace("\r\n", "\n").replace('\r', '\n');
        }
        if (val instanceof java.sql.Timestamp ts) {
            // Normalize to ISO-ish with millisecond precision
            long millis = ts.getTime();
            int nanos = ts.getNanos();
            int ms = nanos / 1_000_000;
            if (nanos % 1_000_000 >= 500_000) ms++; // half-up rounding
            if (ms >= 1000) { millis++; ms -= 1000; }
            return new java.sql.Timestamp(millis).toString().replaceFirst("\\.\\d+$", "")
                    + "." + String.format("%03d", ms);
        }
        if (val instanceof java.sql.Time t) {
            return t.toString(); // HH:MM:SS
        }
        if (val instanceof java.sql.Date d) {
            return d.toString(); // YYYY-MM-DD
        }
        if (val instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (val instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return val.toString();
    }
}
```

- [ ] **Step 2: Write tests for ValueNormalizer**

Add to `src/test/java/com/tool/compare/DataComparatorTest.java`:

```java
@Test
void normalizeNullShouldReturnNull() {
    assertThat(ValueNormalizer.normalize(null)).isNull();
}

@Test
void normalizeBigDecimalShouldStripTrailingZeros() {
    assertThat(ValueNormalizer.normalize(new java.math.BigDecimal("1.00")))
            .isEqualTo("1");
    assertThat(ValueNormalizer.normalize(new java.math.BigDecimal("99.950")))
            .isEqualTo("99.95");
}

@Test
void normalizeStringShouldUnifyLineEndings() {
    assertThat(ValueNormalizer.normalize("a\r\nb"))
            .isEqualTo("a\nb");
    assertThat(ValueNormalizer.normalize("a\rb"))
            .isEqualTo("a\nb");
    assertThat(ValueNormalizer.normalize("a\nb"))
            .isEqualTo("a\nb");
}

@Test
void normalizeBooleanShouldMapToNumeric() {
    assertThat(ValueNormalizer.normalize(true)).isEqualTo("1");
    assertThat(ValueNormalizer.normalize(false)).isEqualTo("0");
}

@Test
void normalizeByteArrayShouldBase64Encode() {
    byte[] data = {0x48, 0x65, 0x6C, 0x6C, 0x6F}; // "Hello"
    assertThat(ValueNormalizer.normalize(data)).isEqualTo("SGVsbG8=");
}
```

- [ ] **Step 3: Run tests**

Run: `mvn test -Dtest=DataComparatorTest -DfailIfNoTests=false 2>&1 | tail -10`
Expected: All tests pass (11 total — 6 config + 5 normalizer)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tool/compare/ValueNormalizer.java src/test/java/com/tool/compare/DataComparatorTest.java
git commit -m "feat: add ValueNormalizer for comparison"
```

---

### Task 7: JdbcDataComparator — table discovery + PK + row count

**Files:**
- Create: `src/main/java/com/tool/compare/JdbcDataComparator.java`
- Test: `src/test/java/com/tool/compare/DataComparatorTest.java`

- [ ] **Step 1: Write the integration-style test with H2**

Add to `DataComparatorTest.java`:

```java
private static Connection newH2Connection(String name) {
    try {
        return java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
    } catch (Exception e) { throw new RuntimeException(e); }
}

private static void exec(Connection c, String sql) {
    try (var s = c.createStatement()) { s.execute(sql); }
    catch (Exception e) { throw new RuntimeException(e); }
}

@Test
void shouldMatchIdenticalTables() {
    Connection src = newH2Connection("src_match");
    Connection tgt = newH2Connection("tgt_match");

    // Create identical tables in both
    for (Connection c : new Connection[]{src, tgt}) {
        exec(c, "CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(50), price DECIMAL(10,2))");
        exec(c, "INSERT INTO orders VALUES (1, 'Apple', 1.50)");
        exec(c, "INSERT INTO orders VALUES (2, 'Banana', 2.00)");
        exec(c, "INSERT INTO orders VALUES (3, 'Orange', 1.75)");
    }

    DataComparator cmp = new JdbcDataComparator();
    ComparisonReport r = cmp.compare(src, tgt,
            ComparisonConfig.defaults("src_match"));

    assertThat(r.totalTables()).isEqualTo(1);
    assertThat(r.matchedTables()).isEqualTo(1);
    assertThat(r.mismatchedTables()).isEqualTo(0);
    assertThat(r.hasMismatches()).isFalse();
}

@Test
void shouldDetectRowCountMismatch() {
    Connection src = newH2Connection("src_rc");
    Connection tgt = newH2Connection("tgt_rc");

    exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
    exec(src, "INSERT INTO t VALUES (1,10), (2,20), (3,30)");

    exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
    exec(tgt, "INSERT INTO t VALUES (1,10), (2,20)");

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            ComparisonConfig.defaults("src_rc"));

    assertThat(r.mismatchedTables()).isEqualTo(1);
    TableComparison tc = r.tables().get(0);
    assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
    assertThat(tc.rowCountSrc()).isEqualTo(3);
    assertThat(tc.rowCountTgt()).isEqualTo(2);
}

@Test
void shouldDetectMissingAndExtraRows() {
    Connection src = newH2Connection("src_me");
    Connection tgt = newH2Connection("tgt_me");

    exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
    exec(src, "INSERT INTO t VALUES (1,10), (2,20), (3,30)");

    exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
    exec(tgt, "INSERT INTO t VALUES (1,10), (2,20), (4,40)");

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            ComparisonConfig.defaults("src_me"));

    TableComparison tc = r.tables().get(0);
    assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
    assertThat(tc.missingInTarget()).contains("(id=3)");
    assertThat(tc.extraInTarget()).contains("(id=4)");
}

@Test
void shouldDetectValueDifferences() {
    Connection src = newH2Connection("src_val");
    Connection tgt = newH2Connection("tgt_val");

    exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT, name VARCHAR(20))");
    exec(src, "INSERT INTO t VALUES (1, 100, 'foo')");

    exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT, name VARCHAR(20))");
    exec(tgt, "INSERT INTO t VALUES (1, 200, 'foo')");

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            ComparisonConfig.defaults("src_val"));

    TableComparison tc = r.tables().get(0);
    assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
    assertThat(tc.columnDiffs()).isNotEmpty();
    ColumnDiff diff = tc.columnDiffs().get(0);
    assertThat(diff.pkValues()).containsEntry("ID", "1");
    assertThat(diff.diffs()).anyMatch(d -> d.column().equals("VAL")
            && d.srcValue().equals("100") && d.tgtValue().equals("200"));
}

@Test
void shouldSkipTableMissingInTarget() {
    Connection src = newH2Connection("src_skip");
    Connection tgt = newH2Connection("tgt_skip");

    exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
    exec(src, "INSERT INTO t VALUES (1,10)");
    // tgt has no table 't'

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            ComparisonConfig.defaults("src_skip"));

    assertThat(r.totalTables()).isEqualTo(1);
    assertThat(r.skippedTables()).isEqualTo(1);
    assertThat(r.matchedTables()).isEqualTo(0);
    assertThat(r.mismatchedTables()).isEqualTo(0);
}

@Test
void shouldSkipTableWithoutPrimaryKey() {
    Connection src = newH2Connection("src_nopk");
    Connection tgt = newH2Connection("tgt_nopk");

    for (Connection c : new Connection[]{src, tgt}) {
        exec(c, "CREATE TABLE t (a INT, b INT)");
        exec(c, "INSERT INTO t VALUES (1,2)");
    }

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            ComparisonConfig.defaults("src_nopk"));

    assertThat(r.skippedTables()).isEqualTo(1);
    TableComparison tc = r.tables().get(0);
    assertThat(tc.status()).isEqualTo(TableComparison.Status.SKIPPED);
}

@Test
void shouldFilterTablesByConfig() {
    Connection src = newH2Connection("src_filter");
    Connection tgt = newH2Connection("tgt_filter");

    for (Connection c : new Connection[]{src, tgt}) {
        exec(c, "CREATE TABLE orders (id INT PRIMARY KEY, v INT)");
        exec(c, "INSERT INTO orders VALUES (1,100)");
        exec(c, "CREATE TABLE items (id INT PRIMARY KEY, v INT)");
        exec(c, "INSERT INTO items VALUES (1,200)");
    }

    ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
            new ComparisonConfig("src_filter", null,
                    List.of(new String[]{"PUBLIC", "orders"}), 5000, 50));

    assertThat(r.totalTables()).isEqualTo(1);
    assertThat(r.tables().get(0).fullName()).contains("orders");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=DataComparatorTest -DfailIfNoTests=false 2>&1 | tail -10`
Expected: compilation error — `JdbcDataComparator` not defined, or 7 new tests fail

- [ ] **Step 3: Write JdbcDataComparator**

```java
package com.tool.compare;

import java.sql.*;
import java.util.*;

public class JdbcDataComparator implements DataComparator {

    @Override
    public ComparisonReport compare(Connection source, Connection target, ComparisonConfig config) {
        int total = 0, matched = 0, mismatched = 0, skipped = 0;
        long totalRowsSrc = 0, totalRowsTgt = 0;
        List<TableComparison> results = new ArrayList<>();

        try {
            String targetCatalog = config.targetCatalog() != null
                    ? config.targetCatalog() : config.catalog();

            List<String[]> tables = config.tables().isEmpty()
                    ? discoverTables(source, config.catalog(), null)
                    : config.tables();

            for (String[] st : tables) {
                String schema = st[0];
                String table = st[1];
                String fullName = schema + "." + table;

                total++;

                // Check target table exists
                if (!tableExists(target, targetCatalog, schema, table)) {
                    skipped++;
                    results.add(new TableComparison(fullName,
                            TableComparison.Status.SKIPPED, 0, 0,
                            List.of(), List.of(), List.of()));
                    continue;
                }

                // Get primary keys
                List<String> srcPk = getPrimaryKeys(source, config.catalog(), schema, table);
                List<String> tgtPk = getPrimaryKeys(target, targetCatalog, schema, table);
                if (srcPk.isEmpty() || tgtPk.isEmpty()) {
                    skipped++;
                    results.add(new TableComparison(fullName,
                            TableComparison.Status.SKIPPED, 0, 0,
                            List.of(), List.of(), List.of()));
                    continue;
                }

                // Row counts
                long srcCount = countRows(source, config.catalog(), schema, table);
                long tgtCount = countRows(target, targetCatalog, schema, table);
                totalRowsSrc += srcCount;
                totalRowsTgt += tgtCount;

                // PK-aligned comparison
                List<String> missing = new ArrayList<>();
                List<String> extra = new ArrayList<>();
                List<ColumnDiff> diffs = compareRows(
                        source, target,
                        config.catalog(), targetCatalog,
                        schema, table, srcPk,
                        config.batchSize(), config.maxMismatchRows(),
                        missing, extra);

                TableComparison.Status status;
                if (srcCount == tgtCount && missing.isEmpty() && extra.isEmpty() && diffs.isEmpty()) {
                    status = TableComparison.Status.MATCHED;
                    matched++;
                } else {
                    status = TableComparison.Status.MISMATCHED;
                    mismatched++;
                }

                results.add(new TableComparison(fullName, status,
                        srcCount, tgtCount, missing, extra, diffs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("comparison failed", e);
        }

        return new ComparisonReport(total, matched, mismatched, skipped,
                totalRowsSrc, totalRowsTgt, results);
    }

    // ── table discovery ──────────────────────────────────────────────────────

    private List<String[]> discoverTables(Connection conn, String catalog,
                                          List<String[]> filter) throws SQLException {
        List<String[]> result = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if (schema == null) schema = "";
                result.add(new String[]{schema, table});
            }
        }
        if (filter != null && !filter.isEmpty()) {
            result.removeIf(t -> filter.stream().noneMatch(f ->
                    f[0].equalsIgnoreCase(t[0]) && f[1].equalsIgnoreCase(t[1])));
        }
        return result;
    }

    private boolean tableExists(Connection conn, String catalog, String schema,
                                String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(catalog, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    // ── primary key discovery ────────────────────────────────────────────────

    private List<String> getPrimaryKeys(Connection conn, String catalog,
                                        String schema, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    // ── row count ────────────────────────────────────────────────────────────

    private long countRows(Connection conn, String catalog, String schema,
                           String table) throws SQLException {
        String sql = buildQualifiedName(catalog, schema, table)
                .map(q -> "SELECT COUNT(*) FROM " + q)
                .orElse("SELECT COUNT(*) FROM \"" + table + "\"");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ── streaming row comparison ─────────────────────────────────────────────

    private List<ColumnDiff> compareRows(
            Connection src, Connection tgt,
            String srcCatalog, String tgtCatalog,
            String schema, String table, List<String> pkCols,
            int batchSize, int maxDiffs,
            List<String> missingOut, List<String> extraOut) throws SQLException {

        List<ColumnDiff> diffs = new ArrayList<>();

        String srcTable = buildQualifiedName(srcCatalog, schema, table)
                .orElse("\"" + table + "\"");
        String tgtTable = buildQualifiedName(tgtCatalog, schema, table)
                .orElse("\"" + table + "\"");

        String orderBy = String.join(", ",
                pkCols.stream().map(c -> "\"" + c + "\"").toList());

        String srcSql = "SELECT * FROM " + srcTable + " ORDER BY " + orderBy;
        String tgtSql = "SELECT * FROM " + tgtTable + " ORDER BY " + orderBy;

        try (Statement srcSt = src.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY);
             Statement tgtSt = tgt.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY)) {

            srcSt.setFetchSize(batchSize);
            tgtSt.setFetchSize(batchSize);

            try (ResultSet srcRs = srcSt.executeQuery(srcSql);
                 ResultSet tgtRs = tgtSt.executeQuery(tgtSql)) {

                boolean srcHas = srcRs.next();
                boolean tgtHas = tgtRs.next();

                while (srcHas && tgtHas) {
                    int cmp = comparePk(srcRs, tgtRs, pkCols);
                    if (cmp < 0) {
                        // Source row not in target
                        if (missingOut.size() < maxDiffs) {
                            missingOut.add(formatPkValues(srcRs, pkCols));
                        }
                        srcHas = srcRs.next();
                    } else if (cmp > 0) {
                        // Target row not in source
                        if (extraOut.size() < maxDiffs) {
                            extraOut.add(formatPkValues(tgtRs, pkCols));
                        }
                        tgtHas = tgtRs.next();
                    } else {
                        // PK match — compare all columns
                        List<ColumnDiff.Diff> colDiffs = compareColumns(srcRs, tgtRs);
                        if (!colDiffs.isEmpty() && diffs.size() < maxDiffs) {
                            diffs.add(new ColumnDiff(
                                    pkValuesMap(srcRs, pkCols), colDiffs));
                        }
                        srcHas = srcRs.next();
                        tgtHas = tgtRs.next();
                    }
                }

                // Remaining source rows = missing in target
                while (srcHas && missingOut.size() < maxDiffs) {
                    missingOut.add(formatPkValues(srcRs, pkCols));
                    srcHas = srcRs.next();
                }
                // Remaining target rows = extra in target
                while (tgtHas && extraOut.size() < maxDiffs) {
                    extraOut.add(formatPkValues(tgtRs, pkCols));
                    tgtHas = tgtRs.next();
                }
            }
        }
        return diffs;
    }

    private int comparePk(ResultSet src, ResultSet tgt, List<String> pkCols)
            throws SQLException {
        for (String col : pkCols) {
            Object sv = src.getObject(col);
            Object tv = tgt.getObject(col);
            int c = compareValues(sv, tv);
            if (c != 0) return c;
        }
        return 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable ca && b instanceof Comparable cb
                && a.getClass().equals(b.getClass())) {
            return ca.compareTo(cb);
        }
        return ValueNormalizer.normalize(a).compareTo(ValueNormalizer.normalize(b));
    }

    private List<ColumnDiff.Diff> compareColumns(ResultSet src, ResultSet tgt)
            throws SQLException {
        List<ColumnDiff.Diff> diffs = new ArrayList<>();
        ResultSetMetaData smd = src.getMetaData();
        ResultSetMetaData tmd = tgt.getMetaData();
        int cols = smd.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String colName = smd.getColumnName(i);
            Object sv = src.getObject(i);
            Object tv;
            try { tv = tgt.getObject(i); }
            catch (SQLException e) { tv = null; }
            if (!valuesEqual(sv, tv)) {
                diffs.add(new ColumnDiff.Diff(colName,
                        ValueNormalizer.normalize(sv),
                        ValueNormalizer.normalize(tv)));
            }
        }
        return diffs;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return ValueNormalizer.normalize(a).equals(ValueNormalizer.normalize(b));
    }

    private String formatPkValues(ResultSet rs, List<String> pkCols) throws SQLException {
        return TableComparison.formatPk(pkValuesMap(rs, pkCols));
    }

    private Map<String, String> pkValuesMap(ResultSet rs, List<String> pkCols)
            throws SQLException {
        Map<String, String> pk = new LinkedHashMap<>();
        for (String col : pkCols) {
            pk.put(col, ValueNormalizer.normalize(rs.getObject(col)));
        }
        return pk;
    }

    // ── qualified name helper ────────────────────────────────────────────────

    /**
     * Build a qualified table reference using JDBC-style quoting.
     * Returns empty if only a bare table name is available (no catalog/schema info).
     */
    private Optional<String> buildQualifiedName(String catalog, String schema, String table) {
        StringBuilder sb = new StringBuilder();
        if (catalog != null && !catalog.isEmpty()) {
            sb.append("\"").append(catalog).append("\".");
        }
        if (schema != null && !schema.isEmpty()) {
            sb.append("\"").append(schema).append("\".");
        }
        sb.append("\"").append(table).append("\"");
        return Optional.of(sb.toString());
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=DataComparatorTest -DfailIfNoTests=false 2>&1 | tail -20`
Expected: All tests pass (18 total)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/compare/JdbcDataComparator.java src/test/java/com/tool/compare/DataComparatorTest.java
git commit -m "feat: add JdbcDataComparator with streaming PK-ordered comparison"
```

---

### Task 8: Rewrite CompareData.java

**Files:**
- Modify: `src/main/java/com/tool/CompareData.java`

- [ ] **Step 1: Rewrite CompareData to use JdbcDataComparator**

```java
package com.tool;

import com.tool.compare.*;

import java.sql.*;
import java.util.*;

/**
 * SQL Server vs TiDB data comparison CLI — uses JdbcDataComparator.
 */
public class CompareData {

    private static final String SS_URL = "jdbc:sqlserver://127.0.0.1:1433;encrypt=false;database=%s";
    private static final String SS_USER = "sa";
    private static final String SS_PASS = "test@123";

    private static final String TIDB_URL = "jdbc:mysql://127.0.0.1:4000/%s?rewriteBatchedStatements=true";
    private static final String TIDB_USER = "root";
    private static final String TIDB_PASS = "";

    public static void main(String[] args) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Class.forName("com.mysql.cj.jdbc.Driver");

        // Discover SQL Server databases
        List<String> dbs = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlserver://127.0.0.1:1433;encrypt=false", SS_USER, SS_PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT name FROM sys.databases WHERE name NOT IN ('master','tempdb','model','msdb') "
                + "AND state_desc = 'ONLINE' ORDER BY name")) {
            while (rs.next()) dbs.add(rs.getString("name"));
        }

        DataComparator cmp = new JdbcDataComparator();

        for (String db : dbs) {
            try (Connection ss = DriverManager.getConnection(
                    String.format(SS_URL, db), SS_USER, SS_PASS);
                 Connection tidb = DriverManager.getConnection(
                    String.format(TIDB_URL, db), TIDB_USER, TIDB_PASS)) {

                ComparisonReport r = cmp.compare(ss, tidb,
                        new ComparisonConfig(db, null, List.of(), 5000, 50));

                System.out.printf("%n=== %s ===%n", db);
                System.out.printf("Tables: %d matched, %d mismatched, %d skipped%n",
                        r.matchedTables(), r.mismatchedTables(), r.skippedTables());
                System.out.printf("Rows:   src=%,d  tgt=%,d%n",
                        r.totalRowsSrc(), r.totalRowsTgt());

                for (TableComparison t : r.tables()) {
                    switch (t.status()) {
                        case MATCHED -> System.out.printf("[OK]   %s  %,d rows%n",
                                t.fullName(), t.rowCountSrc());
                        case MISMATCHED -> {
                            System.out.printf("[MIS]  %s  src=%,d  tgt=%,d%n",
                                    t.fullName(), t.rowCountSrc(), t.rowCountTgt());
                            for (String m : t.missingInTarget()) {
                                System.out.printf("       missing in TiDB: %s%n", m);
                            }
                            for (String e : t.extraInTarget()) {
                                System.out.printf("       extra in TiDB:   %s%n", e);
                            }
                            for (ColumnDiff d : t.columnDiffs()) {
                                System.out.printf("       %s:%n",
                                        TableComparison.formatPk(d.pkValues()));
                                for (ColumnDiff.Diff diff : d.diffs()) {
                                    System.out.printf("         %s: src=%s tgt=%s%n",
                                            diff.column(), diff.srcValue(), diff.tgtValue());
                                }
                            }
                        }
                        case SKIPPED -> System.out.printf("[SKIP] %s%n", t.fullName());
                    }
                }
            } catch (Exception e) {
                System.out.printf("[ERR]  %s: %s%n", db, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -q 2>&1`
Expected: silent success

- [ ] **Step 3: Run all tests**

Run: `mvn test 2>&1 | tail -10`
Expected: All tests pass (18 DataComparatorTest + 10 FilterUtilsTest = 28)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tool/CompareData.java
git commit -m "refactor: rewrite CompareData to use JdbcDataComparator"
```

---

### Task 9: Final verification — clean build + all tests

- [ ] **Step 1: Clean build and run all tests**

```bash
mvn clean test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all 28 tests pass

- [ ] **Step 2: Package and publish**

```bash
mvn package -q -DskipTests && cp target/any2tidb-1.0.0.jar dist/
```

Expected: JAR published successfully
