# DDL Execution Coverage — Type × Default Value Combinations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `DdlExecutionTest` to cover all risky "column type × default value" combinations that can only be validated by actually executing the DDL on TiDB — catching bugs like the `DATETIME(3) DEFAULT CURRENT_TIMESTAMP` precision mismatch before they reach production.

**Architecture:** Each new test case follows the existing `DdlExecutionTest` pattern: build a `TableSchema` programmatically, call `exec()` to convert + execute DDL on live TiDB, assert no error, then optionally query INFORMATION_SCHEMA to verify the stored default. No production code changes — these are test-only additions. All tests are integration tests (require TiDB on 127.0.0.1:4000) and run under `mvn test -Pintegration -Dtest=DdlExecutionTest`.

**Tech Stack:** Java 17, JUnit 5, MySQL JDBC (TiDB-compatible), Spring Boot test infrastructure already in place.

---

## Background: What this plan covers

The `DATETIME(3) DEFAULT CURRENT_TIMESTAMP` bug (Error 1067) was missed because:
1. Unit tests only checked the DDL string, not whether TiDB accepts it.
2. `DdlExecutionTest` tested `DATETIME(3)` columns and `GETDATE()` defaults — but never **combined** them.

This plan adds systematic "type × default" integration tests for every category identified as a risk:

| Category | Risk |
|----------|------|
| `DATETIME(n)` + `GETDATE()` | Precision mismatch (just fixed) — needs regression DE tests |
| `DATE`/`TIME` + `GETDATE()` | TypeMapper produces `CURRENT_TIMESTAMP`; TiDB `DATE` column rejects it |
| `UNIQUEIDENTIFIER` + `NEWID()` | `DEFAULT UUID()` — TiDB may require parens: `DEFAULT (UUID())` |
| `DATETIME` + `SYSDATETIME()` | `DEFAULT CURRENT_TIMESTAMP(6)` — must execute cleanly |
| `DATETIME(n)` + `GETUTCDATE()` | `UTC_TIMESTAMP()` with precision |
| Numeric + literal defaults | `((0))`, `((-1))`, `((1.500000))` stripped correctly |
| String + N-prefix defaults | `(N'CN')` → `'CN'` stripped correctly |
| Empty string default | `('')` → `''` accepted by TiDB |
| `DATETIME2(0)` + `GETDATE()` | scale=0 edge case: should produce `CURRENT_TIMESTAMP` (no precision) |

---

## File Structure

Only one file is modified:

- **Modify:** `src/test/java/com/tool/integration/DdlExecutionTest.java`
  - Add helpers `colDefault()` and `colDefaultLen()` for columns with default values
  - Add test cases DE20–DE30

---

## Helper methods to add (required by all tasks below)

These helpers don't exist yet. Add them to `DdlExecutionTest` alongside the existing `col()`, `colLen()`, `colPrec()` helpers:

```java
private ColumnSchema colDefault(String name, String type, boolean nullable, String defaultValue) {
    ColumnSchema c = col(name, type, nullable);
    c.setDefaultValue(defaultValue);
    return c;
}

private ColumnSchema colDefaultScale(String name, String type, int scale, boolean nullable, String defaultValue) {
    ColumnSchema c = col(name, type, nullable);
    c.setScale(scale);
    c.setDefaultValue(defaultValue);
    return c;
}

private ColumnSchema colDefaultLen(String name, String type, int maxLength, boolean nullable, String defaultValue) {
    ColumnSchema c = colLen(name, type, maxLength, nullable);
    c.setDefaultValue(defaultValue);
    return c;
}
```

---

### Task 1: DE20 — DATETIME(3) + GETDATE() regression (precision-matched default)

This is the regression test for the exact bug that was fixed. It must live in `DdlExecutionTest` so it actually executes the DDL on TiDB.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add the helper methods and DE20 test to DdlExecutionTest**

Open `src/test/java/com/tool/integration/DdlExecutionTest.java`. After the last `// ── helpers ──` block (after `colPrec()`), add the three helper methods shown above. Then add this test after `de19_columnComment()`:

```java
/**
 * DE20: Regression — DATETIME(3) + GETDATE() default must generate
 * CURRENT_TIMESTAMP(3), not bare CURRENT_TIMESTAMP.
 * TiDB rejects precision mismatch with Error 1067.
 */
@Test @Order(20)
void de20_datetime3WithGetdateDefault_precisionMatched() throws Exception {
    String tn = "de_dt3_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema created = colDefaultScale("created_at", "datetime2", 3, false, "(getdate())");

    TableSchema t = table(tn, List.of(id, created), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATETIME(3) + GETDATE() should not produce Error 1067, got: " + r.getErrorMessage());
    assertTrue(tableExists(tn));

    // Verify TiDB stored the default
    try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tn, "created_at")) {
        assertTrue(rs.next());
        String def = rs.getString("COLUMN_DEF");
        assertNotNull(def, "created_at must have a DEFAULT");
        assertTrue(def.toUpperCase().contains("CURRENT_TIMESTAMP"),
                "DEFAULT should be CURRENT_TIMESTAMP variant, got: " + def);
    }
    drop(tn);
}
```

- [ ] **Step 2: Run DE20 alone to verify it passes**

```
cd /home/sev7nyo/ms2tidb
mvn test -Pintegration -Dtest=DdlExecutionTest#de20_datetime3WithGetdateDefault_precisionMatched -pl . 2>&1 | tail -20
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de20): regression — DATETIME(3)+GETDATE() precision-matched default executes on TiDB"
```

---

### Task 2: DE21 — DATE + GETDATE() → TypeMapper produces CURRENT_TIMESTAMP; TiDB DATE column rejects it

`TypeMapper.mapDefaultValue("(getdate())")` always returns `CURRENT_TIMESTAMP`. But a `DATE` column cannot have `DEFAULT CURRENT_TIMESTAMP` in TiDB — TiDB requires `DEFAULT (CURRENT_DATE)` or `DEFAULT CURDATE()` for DATE columns. This test will **expose the bug** if it exists, or confirm the behavior is acceptable.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE21 test**

```java
/**
 * DE21: DATE column with GETDATE() default.
 * TypeMapper maps GETDATE() → CURRENT_TIMESTAMP regardless of column type.
 * TiDB rejects DEFAULT CURRENT_TIMESTAMP on a DATE column.
 * This test documents the current behavior (WARNING expected, not ERROR,
 * because SchemaConverter should either skip the default or translate it).
 * If this test fails with Status.ERROR, a bug exists in TypeMapper/SchemaConverter.
 */
@Test @Order(21)
void de21_dateColumnWithGetdateDefault() throws Exception {
    String tn = "de_date_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema d = colDefault("event_date", "date", true, "(getdate())");

    TableSchema t = table(tn, List.of(id, d), List.of("id"));
    ConversionResult r = exec(t, false);
    // Document actual behavior: if TiDB rejects CURRENT_TIMESTAMP on DATE,
    // status will be ERROR — this test catches the regression.
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATE + GETDATE() default should not produce a fatal DDL error. " +
            "If TiDB rejected DEFAULT CURRENT_TIMESTAMP on DATE column, " +
            "TypeMapper/SchemaConverter must be fixed to emit CURDATE() or drop the default. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE21 to see current behavior**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de21_dateColumnWithGetdateDefault -pl . 2>&1 | tail -30
```

**If PASS**: TiDB accepted it (behavior documented). Proceed.  
**If FAIL with Error 1067**: Bug confirmed. Stop and fix `SchemaConverter`: when `mapDefaultValue()` returns `CURRENT_TIMESTAMP` and the column type maps to `DATE`, replace with `CURDATE()`. Write the fix as a separate commit before proceeding.

- [ ] **Step 3: Commit (after confirming behavior)**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de21): document DATE+GETDATE() default behavior on TiDB"
```

---

### Task 3: DE22 — TIME + GETDATE() → CURRENT_TIMESTAMP on TIME column

Same category as DE21. `TIME` column with `DEFAULT CURRENT_TIMESTAMP` — TiDB may reject this.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE22 test**

```java
/**
 * DE22: TIME column with GETDATE() default.
 * TypeMapper maps GETDATE() → CURRENT_TIMESTAMP.
 * TiDB may reject DEFAULT CURRENT_TIMESTAMP on a TIME column.
 */
@Test @Order(22)
void de22_timeColumnWithGetdateDefault() throws Exception {
    String tn = "de_time_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema t_col = colDefault("event_time", "time", true, "(getdate())");

    TableSchema t = table(tn, List.of(id, t_col), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "TIME + GETDATE() default should not produce a fatal DDL error. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE22 to observe behavior**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de22_timeColumnWithGetdateDefault -pl . 2>&1 | tail -30
```

Same decision tree as DE21: PASS → document; FAIL → fix TypeMapper/SchemaConverter first.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de22): document TIME+GETDATE() default behavior on TiDB"
```

---

### Task 4: DE23 — UNIQUEIDENTIFIER + NEWID() → DEFAULT UUID()

`TypeMapper` maps `NEWID()` → `UUID()`. The DDL emitted is `DEFAULT UUID()`. TiDB requires function-call defaults to be wrapped in parentheses as of MySQL 8.0 mode: `DEFAULT (UUID())`. This test verifies the current output is accepted.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE23 test**

```java
/**
 * DE23: UNIQUEIDENTIFIER + NEWID() default.
 * TypeMapper maps NEWID() → UUID().
 * SchemaConverter emits: `guid` VARCHAR(36) DEFAULT UUID()
 * TiDB in MySQL 8.0 compat mode may require DEFAULT (UUID()).
 * This test documents whether bare UUID() is accepted.
 */
@Test @Order(23)
void de23_uniqueidentifierWithNewidDefault() throws Exception {
    String tn = "de_guid_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema guid = colDefault("guid", "uniqueidentifier", true, "(NEWID())");

    TableSchema t = table(tn, List.of(id, guid), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "UNIQUEIDENTIFIER + NEWID() default should not produce a fatal DDL error. " +
            "If TiDB rejected DEFAULT UUID(), SchemaConverter must wrap it as DEFAULT (UUID()). " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE23**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de23_uniqueidentifierWithNewidDefault -pl . 2>&1 | tail -30
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de23): document UNIQUEIDENTIFIER+NEWID() default behavior on TiDB"
```

---

### Task 5: DE24 — DATETIME + SYSDATETIME() → DEFAULT CURRENT_TIMESTAMP(6)

`TypeMapper` maps `SYSDATETIME()` → `CURRENT_TIMESTAMP(6)`. A plain `DATETIME` column (no precision) with `DEFAULT CURRENT_TIMESTAMP(6)` — TiDB may reject precision on default when column has no precision.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE24 test**

```java
/**
 * DE24: DATETIME + SYSDATETIME() default.
 * TypeMapper maps SYSDATETIME() → CURRENT_TIMESTAMP(6).
 * DATETIME column (no precision) with DEFAULT CURRENT_TIMESTAMP(6):
 * TiDB may reject precision mismatch (DATETIME vs CURRENT_TIMESTAMP(6)).
 */
@Test @Order(24)
void de24_datetimeWithSysdatetimeDefault() throws Exception {
    String tn = "de_sysdatetime";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema ts = colDefault("logged_at", "datetime", false, "(SYSDATETIME())");

    TableSchema t = table(tn, List.of(id, ts), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATETIME + SYSDATETIME() should not produce a fatal DDL error. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE24**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de24_datetimeWithSysdatetimeDefault -pl . 2>&1 | tail -30
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de24): document DATETIME+SYSDATETIME() default behavior on TiDB"
```

---

### Task 6: DE25 — DATETIME(6) + GETUTCDATE() → DEFAULT UTC_TIMESTAMP()

`TypeMapper` maps `GETUTCDATE()` → `UTC_TIMESTAMP()`. Test that `DATETIME(6) DEFAULT UTC_TIMESTAMP()` executes on TiDB (precision of column vs. default function with no precision in parens).

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE25 test**

```java
/**
 * DE25: DATETIME2(6) + GETUTCDATE() default → UTC_TIMESTAMP().
 * Verifies UTC_TIMESTAMP() is accepted as DEFAULT on a DATETIME(6) column.
 */
@Test @Order(25)
void de25_datetime6WithGetutcdateDefault() throws Exception {
    String tn = "de_utcdate_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema ts = colDefaultScale("synced_at", "datetime2", 6, false, "(GETUTCDATE())");

    TableSchema t = table(tn, List.of(id, ts), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATETIME(6) + GETUTCDATE() should not produce a fatal DDL error. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE25**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de25_datetime6WithGetutcdateDefault -pl . 2>&1 | tail -30
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de25): document DATETIME(6)+GETUTCDATE() default behavior on TiDB"
```

---

### Task 7: DE26 — DATETIME2(0) + GETDATE() → scale=0 edge case

When `col.getScale() == 0`, the current code `col.getScale() != null && col.getScale() > 0` is false, so it produces bare `CURRENT_TIMESTAMP` (no precision). `DATETIME2(0)` maps to `DATETIME(6)` (because `fsp=(0>0)?min(0,6):6 = 6`). So `DATETIME(6) DEFAULT CURRENT_TIMESTAMP` — test TiDB accepts this.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE26 test**

```java
/**
 * DE26: DATETIME2(scale=0) + GETDATE() default.
 * TypeMapper maps datetime2(scale=0) → DATETIME(6) (scale=0 falls through to default fsp=6).
 * SchemaConverter: scale=0 is not >0, so default stays CURRENT_TIMESTAMP (no precision).
 * Result: DATETIME(6) DEFAULT CURRENT_TIMESTAMP — TiDB should accept this.
 */
@Test @Order(26)
void de26_datetime2Scale0WithGetdateDefault() throws Exception {
    String tn = "de_dt2_scale0_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    ColumnSchema ts = colDefaultScale("created_at", "datetime2", 0, false, "(getdate())");

    TableSchema t = table(tn, List.of(id, ts), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATETIME2(scale=0) + GETDATE() should not produce a fatal error. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE26**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de26_datetime2Scale0WithGetdateDefault -pl . 2>&1 | tail -30
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de26): document DATETIME2(scale=0)+GETDATE() edge case on TiDB"
```

---

### Task 8: DE27 — Numeric literal defaults (negative, decimal, double-wrapped parens)

Verify that `((0))` → `0`, `((-1))` → `-1`, `((1.500000))` → `1.500000` are all accepted by TiDB as literal defaults on appropriate column types.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE27 test**

```java
/**
 * DE27: Numeric literal defaults — double-wrapped parens, negative, decimal.
 * SQL Server stores: ((0)), ((-1)), ((1.500000))
 * TypeMapper strips parens → 0, -1, 1.500000
 * Verify TiDB accepts these as DEFAULT values.
 */
@Test @Order(27)
void de27_numericLiteralDefaults() throws Exception {
    String tn = "de_numeric_defaults";
    drop(tn);
    ColumnSchema id     = col("id", "int", false);
    ColumnSchema flag   = colDefault("flag",   "bit",          false, "((0))");
    ColumnSchema score  = colDefault("score",  "int",          true,  "((-1))");
    ColumnSchema amount = colDefault("amount", "decimal",      true,  "((1.500000))");
    amount.setPrecision(10); amount.setScale(6);
    ColumnSchema big    = colDefault("big",    "bigint",       true,  "((0))");

    TableSchema t = table(tn, List.of(id, flag, score, amount, big), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "Numeric literal defaults should all execute on TiDB. Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));

    // Verify defaults are actually stored
    try (Statement st = tidbConn.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT COLUMN_NAME, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS " +
             "WHERE TABLE_SCHEMA = 'testdb' AND TABLE_NAME = '" + tn + "' " +
             "AND COLUMN_NAME IN ('flag','score') ORDER BY COLUMN_NAME")) {
        int count = 0;
        while (rs.next()) {
            count++;
            String colName = rs.getString("COLUMN_NAME");
            String def = rs.getString("COLUMN_DEFAULT");
            assertNotNull(def, colName + " must have a DEFAULT");
        }
        assertEquals(2, count, "flag and score must both appear in INFORMATION_SCHEMA");
    }
    drop(tn);
}
```

- [ ] **Step 2: Run DE27**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de27_numericLiteralDefaults -pl . 2>&1 | tail -30
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de27): numeric literal defaults (double-paren, negative, decimal) execute on TiDB"
```

---

### Task 9: DE28 — String defaults: N-prefix stripped, empty string

SQL Server stores `(N'CN')` for nvarchar defaults and `('')` for empty string. TypeMapper strips parens and the N-prefix is stripped by the paren loop (since `N'CN'` is left as-is after paren removal). Verify TiDB accepts `DEFAULT 'CN'` and `DEFAULT ''`.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE28 test**

```java
/**
 * DE28: String literal defaults — N-prefix handling and empty string.
 * SQL Server stores: (N'CN') for nvarchar, ('') for empty string.
 * TypeMapper strips outer parens → N'CN' or ''
 * Note: N'CN' (with N prefix) may or may not be accepted by TiDB as a default.
 * This test documents behavior.
 */
@Test @Order(28)
void de28_stringLiteralDefaults() throws Exception {
    String tn = "de_string_defaults";
    drop(tn);
    ColumnSchema id      = col("id", "int", false);
    ColumnSchema country = colDefaultLen("country", "nvarchar", 4, false, "(N'CN')");  // nvarchar(2) → 4 bytes → VARCHAR(2)
    ColumnSchema code    = colDefaultLen("code",    "varchar",  10, true, "('')");     // empty string default

    TableSchema t = table(tn, List.of(id, country, code), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "String literal defaults (N'CN', '') should execute on TiDB. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));

    // Verify country default stored
    try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tn, "country")) {
        assertTrue(rs.next());
        String def = rs.getString("COLUMN_DEF");
        assertNotNull(def, "country must have a DEFAULT");
        // TiDB may store CN or N'CN' — just check it's non-null
    }
    drop(tn);
}
```

- [ ] **Step 2: Run DE28**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de28_stringLiteralDefaults -pl . 2>&1 | tail -30
```

**If FAIL** with syntax error on `N'CN'`: TypeMapper must strip the `N` prefix from string literals before emitting. Fix: in `mapDefaultValue()`, after stripping parens, if `val` matches `^N'.*'$`, strip the leading `N`. Write as a separate commit with a unit test in `TypeMapperTest` first.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de28): string literal defaults (N-prefix, empty string) on TiDB"
```

---

### Task 10: DE29 — All DATETIME precision variants + GETDATE() (comprehensive matrix)

Cover `DATETIME2(1)` through `DATETIME2(6)` each with `GETDATE()`, ensuring the `CURRENT_TIMESTAMP(n)` precision always matches the column's `DATETIME(n)`.

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add DE29 test**

```java
/**
 * DE29: DATETIME2 precision matrix × GETDATE() default.
 * For each scale 1..6: column is DATETIME(n), default must be CURRENT_TIMESTAMP(n).
 * TiDB Error 1067 if precision doesn't match.
 */
@Test @Order(29)
void de29_datetime2PrecisionMatrix_withGetdateDefault() throws Exception {
    String tn = "de_dt_matrix";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    // Build one table with dt1..dt6 columns, each with GETDATE() default
    java.util.List<ColumnSchema> cols = new java.util.ArrayList<>();
    cols.add(id);
    for (int scale = 1; scale <= 6; scale++) {
        cols.add(colDefaultScale("dt" + scale, "datetime2", scale, false, "(getdate())"));
    }
    TableSchema t = table(tn, cols, List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DATETIME2 precision 1-6 with GETDATE() default must all execute without Error 1067. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    drop(tn);
}
```

- [ ] **Step 2: Run DE29**

```
mvn test -Pintegration -Dtest=DdlExecutionTest#de29_datetime2PrecisionMatrix_withGetdateDefault -pl . 2>&1 | tail -30
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(de29): DATETIME2 scale 1-6 × GETDATE() full precision matrix on TiDB"
```

---

### Task 11: DE30 — Run full DdlExecutionTest suite (DE01–DE30) and confirm all pass

After all new tests are added, run the complete suite to ensure no regressions.

**Files:**
- No code changes. Verification only.

- [ ] **Step 1: Run full DdlExecutionTest**

```
cd /home/sev7nyo/ms2tidb
mvn test -Pintegration -Dtest=DdlExecutionTest -pl . 2>&1 | tail -30
```

Expected output:
```
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

- [ ] **Step 2: If any test fails due to a discovered bug, follow TDD discipline**

For each failure:
1. The failing test IS the regression test (already written).
2. Identify which class needs fixing (`TypeMapper`, `SchemaConverter`, or `VerifyResult`).
3. Write a **unit test** reproducing the same bug in `TypeMapperTest` or `SchemaConverterTest`.
4. Confirm the unit test fails.
5. Fix the production code.
6. Confirm both unit test and DE test pass.
7. Commit the unit test and fix together.

- [ ] **Step 3: Final commit**

```bash
git add .
git commit -m "test: complete DE20-DE30 type×default coverage matrix, all passing on TiDB"
```

---

## Self-Review Checklist

### 1. Spec coverage

| Identified Risk | Task |
|----------------|------|
| `DATETIME(3)` + `GETDATE()` regression | DE20 ✅ |
| `DATE` + `GETDATE()` | DE21 ✅ |
| `TIME` + `GETDATE()` | DE22 ✅ |
| `UNIQUEIDENTIFIER` + `NEWID()` | DE23 ✅ |
| `DATETIME` + `SYSDATETIME()` | DE24 ✅ |
| `DATETIME(6)` + `GETUTCDATE()` | DE25 ✅ |
| `DATETIME2(0)` scale=0 edge case | DE26 ✅ |
| Numeric literal defaults | DE27 ✅ |
| String defaults (N-prefix, empty) | DE28 ✅ |
| Full precision matrix 1–6 | DE29 ✅ |
| Full suite verification | DE30 ✅ |

### 2. Placeholder scan

None found. All steps contain complete test code and exact commands.

### 3. Type consistency

All helpers (`colDefault`, `colDefaultScale`, `colDefaultLen`) are defined once in the header section and used identically in all tasks. `colDefaultScale` sets `col.setScale()` which matches `SchemaConverter`'s check of `col.getScale()`.
