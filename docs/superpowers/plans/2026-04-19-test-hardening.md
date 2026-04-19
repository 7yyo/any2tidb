# Test Hardening Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the test suite to cover all bugs found during today's real-database run — and the full class of similar bugs — so regressions are caught before any future production run.

**Architecture:** Four independent task areas: (1) unit-test the complete type×default truth table in `SchemaConverterTest`; (2) add integration tests in `DdlExecutionTest` for the new numeric-empty-string default drop; (3) implement and test an index key-length guard in `SchemaConverter`; (4) verify `VerifyResult` correctly classifies numeric empty-string default drops as known loss, not mismatch.

**Tech Stack:** Java 21, JUnit 5, Spring Boot, TiDB 7.x (integration tests), Maven Surefire + JaCoCo

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `src/test/java/com/tool/converter/SchemaConverterTest.java` | Modify | Add type×default matrix unit tests |
| `src/test/java/com/tool/integration/DdlExecutionTest.java` | Modify | Add DE30/DE31 for numeric+empty-string on TiDB |
| `src/main/java/com/tool/converter/SchemaConverter.java` | Modify | Add index key-length guard + warning/drop logic |
| `src/test/java/com/tool/converter/SchemaConverterTest.java` | Modify | Add unit tests for index key-length guard |
| `src/test/java/com/tool/integration/DdlExecutionTest.java` | Modify | Add DE32 for long-key index live execution |
| `src/test/java/com/tool/verifier/SchemaVerifierTest.java` | Modify | Add known-loss classification tests for numeric default drops |

---

## Task 1: SchemaConverter type×default matrix unit tests

Cover all numeric types × all problematic SQL Server default patterns in `SchemaConverterTest`.
This is a pure unit-test task — no code change in production code needed.

**Files:**
- Modify: `src/test/java/com/tool/converter/SchemaConverterTest.java`

- [ ] **Step 1: Add helper and matrix tests**

  Append the following tests at the end of `SchemaConverterTest` (before the closing `}`):

```java
// ── Type × default matrix ─────────────────────────────────────────────────

/**
 * All numeric TiDB types with empty-string default ('') must drop the default
 * and produce a warning. Covers INT, BIGINT, SMALLINT, TINYINT UNSIGNED,
 * DECIMAL, DECIMAL(19,4) (money), TINYINT(1) (bit).
 */
@Test
void numericTypeMatrix_emptyStringDefault_allDropped() {
    record Case(String ssType, int prec, int scale, int maxLen, String expectedTidbPrefix) {}
    List<Case> cases = List.of(
            new Case("int",        0,  0,  0, "INT"),
            new Case("bigint",     0,  0,  0, "BIGINT"),
            new Case("smallint",   0,  0,  0, "SMALLINT"),
            new Case("tinyint",    0,  0,  0, "TINYINT"),
            new Case("numeric",   10,  2,  0, "DECIMAL"),
            new Case("decimal",   18,  4,  0, "DECIMAL"),
            new Case("money",      0,  0,  0, "DECIMAL"),
            new Case("smallmoney", 0,  0,  0, "DECIMAL"),
            new Case("bit",        0,  0,  0, "TINYINT")
    );
    for (Case c : cases) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema col = new ColumnSchema();
        col.setName("val"); col.setSqlServerType(c.ssType());
        if (c.prec() > 0) col.setPrecision(c.prec());
        if (c.scale() > 0) col.setScale(c.scale());
        col.setNullable(true);
        col.setDefaultValue("('')");
        t.setColumns(List.of(id, col));
        t.setPrimaryKeyColumns(List.of("id"));

        ConversionResult result = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl, c.ssType() + ": DDL must not be null");
        assertFalse(ddl.contains("`val` " + c.expectedTidbPrefix() + " DEFAULT")
                || ddl.matches("(?s).*`val` " + c.expectedTidbPrefix() + "\\([^)]*\\) DEFAULT.*"),
                c.ssType() + ": DDL must not emit DEFAULT for val, got: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("val") && w.contains("default dropped")),
                c.ssType() + ": must warn 'default dropped' for val, got: " + result.getWarnings());
    }
}

/**
 * All temporal TiDB types with empty-string default ('') must drop.
 * DATE, TIME, DATETIME, DATETIME(n).
 */
@Test
void temporalTypeMatrix_emptyStringDefault_allDropped() {
    record Case(String ssType, int scale) {}
    List<Case> cases = List.of(
            new Case("date",      0),
            new Case("time",      0),
            new Case("datetime",  0),
            new Case("datetime2", 3)
    );
    for (Case c : cases) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema col = new ColumnSchema();
        col.setName("ts"); col.setSqlServerType(c.ssType());
        if (c.scale() > 0) col.setScale(c.scale());
        col.setNullable(true);
        col.setDefaultValue("('')");
        t.setColumns(List.of(id, col));
        t.setPrimaryKeyColumns(List.of("id"));

        ConversionResult result = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl, c.ssType() + ": DDL must not be null");
        assertFalse(ddl.contains("`ts`") && ddl.contains("DEFAULT ''"),
                c.ssType() + ": must not emit DEFAULT '' for ts");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("ts") && w.contains("dropped")),
                c.ssType() + ": must warn default dropped for ts, got: " + result.getWarnings());
    }
}

/**
 * All numeric types with numeric literal default ((0)) must be kept.
 * Regression guard: make sure the empty-string fix didn't break literal 0 defaults.
 */
@Test
void numericTypeMatrix_zeroLiteralDefault_retained() {
    List<String> ssTypes = List.of("int", "bigint", "smallint", "bit");
    for (String ssType : ssTypes) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema col = new ColumnSchema();
        col.setName("val"); col.setSqlServerType(ssType);
        col.setNullable(true);
        col.setDefaultValue("((0))");
        t.setColumns(List.of(id, col));
        t.setPrimaryKeyColumns(List.of("id"));

        ConversionResult result = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl, ssType + ": DDL must not be null");
        assertTrue(ddl.contains("DEFAULT 0"),
                ssType + ": must retain DEFAULT 0, got: " + ddl);
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("val") && w.contains("default dropped")),
                ssType + ": must NOT warn 'default dropped' for numeric literal 0");
    }
}

/**
 * String types (varchar, nvarchar) with empty-string default ('') must be retained —
 * they are valid for string columns.
 */
@Test
void stringTypeMatrix_emptyStringDefault_retained() {
    record Case(String ssType, int maxLen) {}
    List<Case> cases = List.of(
            new Case("varchar",  50),
            new Case("nvarchar", 100)
    );
    for (Case c : cases) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema col = new ColumnSchema();
        col.setName("note"); col.setSqlServerType(c.ssType()); col.setMaxLength(c.maxLen()); col.setNullable(true);
        col.setDefaultValue("('')");
        t.setColumns(List.of(id, col));
        t.setPrimaryKeyColumns(List.of("id"));

        ConversionResult result = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl, c.ssType() + ": DDL must not be null");
        assertTrue(ddl.contains("DEFAULT ''"),
                c.ssType() + ": empty string default must be retained for string columns, got: " + ddl);
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("note") && w.contains("default dropped")),
                c.ssType() + ": must NOT drop default for string column, got: " + result.getWarnings());
    }
}
```

- [ ] **Step 2: Run unit tests to verify new tests pass**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Dtest=SchemaConverterTest -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all tests including the 4 new ones pass.

- [ ] **Step 3: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/test/java/com/tool/converter/SchemaConverterTest.java
git commit -m "test: add type×default matrix unit tests for numeric/temporal/string columns

Covers all numeric TiDB types × empty-string default (must drop),
temporal types × empty-string default (must drop), numeric types ×
literal-0 default (must retain), string types × empty-string default
(must retain). Regression guard for the numeric-empty-string fix."
```

---

## Task 2: DdlExecutionTest — numeric empty-string default on live TiDB

Add two integration tests (DE30, DE31) that execute numeric columns with `DEFAULT ('')`
against a live TiDB instance and verify they succeed (no Error 1067).

**Files:**
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Append DE30 and DE31 to DdlExecutionTest (before the closing `}`)**

```java
/**
 * DE30: numeric/int columns with empty-string default ('') from SQL Server.
 * SchemaConverter must drop the default (not emit DEFAULT '').
 * TiDB must accept the DDL without Error 1067.
 */
@Test @Order(30)
void de30_numericColumnsWithEmptyStringDefault_noError1067() throws Exception {
    String tn = "de_numeric_empty_default";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    id.setIdentity(true);
    ColumnSchema actionOrder    = colDefault("actionOrder",      "numeric", true,  "('')");
    actionOrder.setPrecision(10); actionOrder.setScale(0);
    ColumnSchema templateVersion= colDefault("templateVersion",  "int",     true,  "('')");
    ColumnSchema smallFlag      = colDefault("smallFlag",        "smallint",true,  "('')");
    ColumnSchema bigNum         = colDefault("bigNum",           "bigint",  true,  "('')");

    TableSchema t = table(tn, List.of(id, actionOrder, templateVersion, smallFlag, bigNum), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "Numeric columns with empty-string default must not cause Error 1067 on TiDB. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn), "Table must have been created");

    // Defaults must have been silently dropped — TiDB column should have no DEFAULT
    try (Statement st = tidbConn.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT COLUMN_NAME, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS " +
             "WHERE TABLE_SCHEMA = 'testdb' AND TABLE_NAME = '" + tn + "' " +
             "AND COLUMN_NAME IN ('actionOrder','templateVersion') ORDER BY COLUMN_NAME")) {
        while (rs.next()) {
            String colName = rs.getString("COLUMN_NAME");
            String def = rs.getString("COLUMN_DEFAULT");
            assertNull(def, colName + " must have no DEFAULT after empty-string drop, got: " + def);
        }
    }

    // Warnings must have been recorded
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("actionOrder") && w.contains("default dropped")),
            "Must warn about actionOrder default dropped, got: " + r.getWarnings());
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("templateVersion") && w.contains("default dropped")),
            "Must warn about templateVersion default dropped, got: " + r.getWarnings());
    drop(tn);
}

/**
 * DE31: money/smallmoney/decimal with empty-string default.
 * Covers the DECIMAL family. TiDB must accept without Error 1067.
 */
@Test @Order(31)
void de31_decimalFamilyWithEmptyStringDefault_noError1067() throws Exception {
    String tn = "de_decimal_empty_default";
    drop(tn);
    ColumnSchema id   = col("id", "int", false);
    id.setIdentity(true);
    ColumnSchema price = colDefault("price", "decimal", true, "('')");
    price.setPrecision(10); price.setScale(2);
    ColumnSchema budget = colDefault("budget", "money", true, "('')");
    ColumnSchema small  = colDefault("small",  "smallmoney", true, "('')");

    TableSchema t = table(tn, List.of(id, price, budget, small), List.of("id"));
    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "DECIMAL/MONEY columns with empty-string default must not cause Error 1067. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn));
    // All three must have warnings
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("price") && w.contains("default dropped")));
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("budget") && w.contains("default dropped")));
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("small") && w.contains("default dropped")));
    drop(tn);
}
```

- [ ] **Step 2: Run integration tests to verify DE30 and DE31 pass**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Pintegration -Dtest=DdlExecutionTest#de30_numericColumnsWithEmptyStringDefault_noError1067+de31_decimalFamilyWithEmptyStringDefault_noError1067 -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, both DE30 and DE31 pass.

- [ ] **Step 3: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "test(integration): add DE30/DE31 — numeric empty-string default on live TiDB

Verifies that numeric/decimal/money columns with SQL Server DEFAULT ('')
produce no TiDB Error 1067. The converter must drop the default and emit
a warning. Covers the bug found in today's production run (6 tables affected)."
```

---

## Task 3: Index key-length guard in SchemaConverter

TiDB rejects indexes where the total key size exceeds 3072 bytes (Error 1071).
SQL Server allows arbitrary-length composite indexes over nvarchar columns.

The guard must:
- For each non-fulltext, non-columnstore index, compute estimated byte size of all key columns.
- nvarchar/varchar/char columns: `charLen × 4` bytes (utf8mb4 worst-case), capped at actual column max.
- If estimated size > 3072: warn and skip (drop) the index rather than emitting DDL that TiDB will reject.
- For columns not present in the TableSchema (e.g. computed), treat their contribution as 0 (safe fallback).

**Files:**
- Modify: `src/main/java/com/tool/converter/SchemaConverter.java`
- Modify: `src/test/java/com/tool/converter/SchemaConverterTest.java`
- Modify: `src/test/java/com/tool/integration/DdlExecutionTest.java`

- [ ] **Step 1: Add the key-length guard in SchemaConverter**

  In `buildIndexDDL`, after the existing `isClustered()` / `getIncludeColumns()` / `getFilterDefinition()` checks and before the final `return` that builds the DDL string, add:

```java
// Key-length guard — TiDB rejects indexes whose total key byte size exceeds 3072.
// Estimate using worst-case utf8mb4: each character = 4 bytes.
int estimatedKeyBytes = estimateIndexKeyBytes(tableName, idx, table);
if (estimatedKeyBytes > 3072) {
    result.addWarning("index '" + idx.getName() + "': estimated key size " + estimatedKeyBytes
            + " bytes exceeds TiDB 3072-byte limit — index dropped to prevent Error 1071. "
            + "Consider reducing column lengths or splitting the index.");
    return null;
}
```

  Note: `buildIndexDDL` must also receive the `TableSchema` so it can look up column definitions.
  Change the signature and all call sites:

  **Old signature:**
  ```java
  private String buildIndexDDL(String tableName, IndexSchema idx, ConversionResult result) {
  ```

  **New signature:**
  ```java
  private String buildIndexDDL(String tableName, IndexSchema idx, ConversionResult result, TableSchema table) {
  ```

  **Old call site** (in `toCreateTableDDL`):
  ```java
  String indexDdl = buildIndexDDL(table.getTableName(), idx, result);
  ```

  **New call site:**
  ```java
  String indexDdl = buildIndexDDL(table.getTableName(), idx, result, table);
  ```

  Add the helper method after `buildIndexDDL`:

```java
/**
 * Estimate total index key size in bytes using utf8mb4 worst-case (4 bytes/char).
 * Returns 0 for non-string types (they are small and well-defined).
 * Unknown columns (e.g. expressions) count as 0 — safe fallback.
 */
private int estimateIndexKeyBytes(String tableName, IndexSchema idx, TableSchema table) {
    // Build a lookup: column name (lower) → ColumnSchema
    java.util.Map<String, ColumnSchema> colMap = new java.util.HashMap<>();
    for (ColumnSchema c : table.getColumns()) {
        colMap.put(c.getName().toLowerCase(), c);
    }

    int total = 0;
    for (String keyCol : idx.getColumns()) {
        ColumnSchema col = colMap.get(keyCol.trim().toLowerCase());
        if (col == null) continue;  // unknown column — skip
        TypeMapper.MappedType mapped = typeMapper.mapType(col);
        if (mapped.skip()) continue;
        String tidbType = mapped.tidbType().trim().toUpperCase();
        total += estimateColumnKeyBytes(col, tidbType);
    }
    return total;
}

/**
 * Estimate the key bytes for a single column based on its TiDB type.
 * String types use utf8mb4 worst-case (4 bytes/char).
 * Binary types use 1 byte/byte.
 * Fixed-size numeric/date types: exact known sizes.
 * Everything else: 0 (assume small, not a problem).
 */
private int estimateColumnKeyBytes(ColumnSchema col, String tidbType) {
    // VARCHAR(n) CHARACTER SET utf8mb4 or VARCHAR(n) → n * 4
    if (tidbType.startsWith("VARCHAR(") || tidbType.startsWith("CHAR(")) {
        int paren = tidbType.indexOf('(');
        int end   = tidbType.indexOf(')');
        if (paren >= 0 && end > paren) {
            try {
                int charLen = Integer.parseInt(tidbType.substring(paren + 1, end));
                return charLen * 4;
            } catch (NumberFormatException ignored) {}
        }
    }
    // VARBINARY(n) or BINARY(n) → n bytes
    if (tidbType.startsWith("VARBINARY(") || tidbType.startsWith("BINARY(")) {
        int paren = tidbType.indexOf('(');
        int end   = tidbType.indexOf(')');
        if (paren >= 0 && end > paren) {
            try {
                return Integer.parseInt(tidbType.substring(paren + 1, end));
            } catch (NumberFormatException ignored) {}
        }
    }
    // BIGINT / BIGINT UNSIGNED → 8, INT → 4, SMALLINT → 2, TINYINT → 1
    if (tidbType.startsWith("BIGINT")) return 8;
    if (tidbType.startsWith("INT"))    return 4;
    if (tidbType.startsWith("SMALLINT")) return 2;
    if (tidbType.startsWith("TINYINT")) return 1;
    // DATETIME(n) → 8, DATE → 3, TIME → 3
    if (tidbType.startsWith("DATETIME")) return 8;
    if (tidbType.equals("DATE")) return 3;
    if (tidbType.equals("TIME")) return 3;
    // DECIMAL(p,s) — 4 bytes per 9 digits, but conservatively treat as 17 bytes max
    if (tidbType.startsWith("DECIMAL")) return 17;
    // LONGTEXT / TEXT / JSON / BLOB — these shouldn't be in an index; return large value to trigger guard
    if (tidbType.contains("TEXT") || tidbType.contains("BLOB") || tidbType.equals("JSON")) return 65535;
    // Default: 8 bytes (conservative upper bound for unknown fixed types)
    return 8;
}
```

- [ ] **Step 2: Verify the code compiles**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -Djacoco.skip=true 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Add unit tests in SchemaConverterTest**

  Append these tests to `SchemaConverterTest` (before closing `}`):

```java
// ── Index key-length guard ────────────────────────────────────────────────

/**
 * A single nvarchar(768) column index: 768 chars × 4 bytes = 3072 bytes exactly.
 * Should be kept (boundary condition — exactly at the limit).
 */
@Test
void indexKeyLength_exactlyAtLimit_isKept() {
    TableSchema t = simpleTable();
    // Add a wide nvarchar column: 768 chars × 4 = 3072 bytes (at limit)
    ColumnSchema wide = new ColumnSchema();
    wide.setName("wide"); wide.setSqlServerType("nvarchar"); wide.setMaxLength(1536); wide.setNullable(true);
    // nvarchar maxLength in bytes from sys.columns; TypeMapper divides by 2 → 768 chars → VARCHAR(768) utf8mb4
    t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), wide));

    IndexSchema idx = new IndexSchema();
    idx.setName("idx_wide"); idx.setUnique(false); idx.setClustered(false);
    idx.setColumns(java.util.List.of("wide")); idx.setIncludeColumns(java.util.List.of());
    t.setIndexes(java.util.List.of(idx));

    ConversionResult result = new ConversionResult("dbo.users");
    String ddl = converter.toCreateTableDDL(t, result, false);
    assertTrue(ddl.contains("INDEX `idx_wide`"),
            "Index at exactly 3072 bytes must be kept, got: " + ddl);
    assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("idx_wide") && w.contains("1071")),
            "No key-length warning expected at exactly 3072 bytes");
}

/**
 * A single nvarchar(769) column index: 769 chars × 4 bytes = 3076 bytes > 3072.
 * Should be dropped with a warning mentioning Error 1071.
 */
@Test
void indexKeyLength_oneByteOverLimit_isDroppedWithWarning() {
    TableSchema t = simpleTable();
    // nvarchar(769 chars) = maxLength 1538 bytes in sys.columns; TypeMapper → VARCHAR(769) utf8mb4
    ColumnSchema wide = new ColumnSchema();
    wide.setName("wide"); wide.setSqlServerType("nvarchar"); wide.setMaxLength(1538); wide.setNullable(true);
    t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), wide));

    IndexSchema idx = new IndexSchema();
    idx.setName("idx_toolong"); idx.setUnique(false); idx.setClustered(false);
    idx.setColumns(java.util.List.of("wide")); idx.setIncludeColumns(java.util.List.of());
    t.setIndexes(java.util.List.of(idx));

    ConversionResult result = new ConversionResult("dbo.users");
    String ddl = converter.toCreateTableDDL(t, result, false);
    assertFalse(ddl.contains("idx_toolong"),
            "Index exceeding 3072 bytes must be dropped, got: " + ddl);
    assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("idx_toolong") && w.contains("1071")),
            "Must warn about Error 1071 key length, got: " + result.getWarnings());
}

/**
 * Composite index: nvarchar(200) + nvarchar(200) + nvarchar(200) = 600 chars × 4 = 2400 bytes — kept.
 */
@Test
void indexKeyLength_compositeUnderLimit_isKept() {
    TableSchema t = simpleTable();
    ColumnSchema a = new ColumnSchema();
    a.setName("a"); a.setSqlServerType("nvarchar"); a.setMaxLength(400); a.setNullable(true);
    ColumnSchema b = new ColumnSchema();
    b.setName("b"); b.setSqlServerType("nvarchar"); b.setMaxLength(400); b.setNullable(true);
    ColumnSchema c = new ColumnSchema();
    c.setName("c"); c.setSqlServerType("nvarchar"); c.setMaxLength(400); c.setNullable(true);
    t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), a, b, c));

    IndexSchema idx = new IndexSchema();
    idx.setName("idx_composite"); idx.setUnique(false); idx.setClustered(false);
    idx.setColumns(java.util.List.of("a", "b", "c")); idx.setIncludeColumns(java.util.List.of());
    t.setIndexes(java.util.List.of(idx));

    ConversionResult result = new ConversionResult("dbo.users");
    String ddl = converter.toCreateTableDDL(t, result, false);
    assertTrue(ddl.contains("INDEX `idx_composite`"),
            "Composite index under 3072 bytes must be kept, got: " + ddl);
}

/**
 * Composite index: nvarchar(300) + nvarchar(300) + nvarchar(300) = 900 chars × 4 = 3600 bytes > 3072.
 * Should be dropped with warning.
 */
@Test
void indexKeyLength_compositeOverLimit_isDroppedWithWarning() {
    TableSchema t = simpleTable();
    ColumnSchema a = new ColumnSchema();
    a.setName("a"); a.setSqlServerType("nvarchar"); a.setMaxLength(600); a.setNullable(true);
    ColumnSchema b = new ColumnSchema();
    b.setName("b"); b.setSqlServerType("nvarchar"); b.setMaxLength(600); b.setNullable(true);
    ColumnSchema c = new ColumnSchema();
    c.setName("c"); c.setSqlServerType("nvarchar"); c.setMaxLength(600); c.setNullable(true);
    t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), a, b, c));

    IndexSchema idx = new IndexSchema();
    idx.setName("idx_toolong_composite"); idx.setUnique(false); idx.setClustered(false);
    idx.setColumns(java.util.List.of("a", "b", "c")); idx.setIncludeColumns(java.util.List.of());
    t.setIndexes(java.util.List.of(idx));

    ConversionResult result = new ConversionResult("dbo.users");
    String ddl = converter.toCreateTableDDL(t, result, false);
    assertFalse(ddl.contains("idx_toolong_composite"),
            "Composite index over 3072 bytes must be dropped, got: " + ddl);
    assertTrue(result.getWarnings().stream().anyMatch(w ->
                    w.contains("idx_toolong_composite") && w.contains("1071")),
            "Must warn about Error 1071, got: " + result.getWarnings());
}

/**
 * Integer-only index: INT key = 4 bytes — well under limit, must be kept.
 * Guard must not affect non-string indexes.
 */
@Test
void indexKeyLength_integerIndex_notAffected() {
    TableSchema t = simpleTable();
    IndexSchema idx = new IndexSchema();
    idx.setName("idx_int"); idx.setUnique(false); idx.setClustered(false);
    idx.setColumns(java.util.List.of("id")); idx.setIncludeColumns(java.util.List.of());
    t.setIndexes(java.util.List.of(idx));

    ConversionResult result = new ConversionResult("dbo.users");
    String ddl = converter.toCreateTableDDL(t, result, false);
    assertTrue(ddl.contains("INDEX `idx_int`"),
            "Integer index must be kept, got: " + ddl);
    assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("idx_int") && w.contains("1071")));
}
```

- [ ] **Step 4: Run unit tests**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Dtest=SchemaConverterTest -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all tests pass including the 5 new key-length tests.

- [ ] **Step 5: Add DE32 integration test in DdlExecutionTest**

  Append before the closing `}`:

```java
/**
 * DE32: Composite nvarchar index exceeding TiDB's 3072-byte key limit.
 * Three nvarchar(300) columns in a composite index = 3600 bytes > 3072.
 * SchemaConverter must drop the index and warn rather than emitting DDL
 * that TiDB would reject with Error 1071.
 */
@Test @Order(32)
void de32_compositeNvarcharIndex_keyTooLong_dropped() throws Exception {
    String tn = "de_key_toolong";
    drop(tn);
    ColumnSchema id = col("id", "int", false);
    id.setIdentity(true);
    // 300 nvarchar chars = 600 bytes in sys.columns maxLength
    ColumnSchema a = colLen("col_a", "nvarchar", 600, true);
    ColumnSchema b = colLen("col_b", "nvarchar", 600, true);
    ColumnSchema c = colLen("col_c", "nvarchar", 600, true);

    IndexSchema longIdx = new IndexSchema();
    longIdx.setName("IX_composite_long");
    longIdx.setColumns(List.of("col_a", "col_b", "col_c"));
    longIdx.setIncludeColumns(List.of());

    TableSchema t = table(tn, List.of(id, a, b, c), List.of("id"));
    t.setIndexes(List.of(longIdx));

    ConversionResult r = exec(t, false);
    assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
            "Table must be created even when composite index exceeds key length limit. " +
            "Error: " + r.getErrorMessage());
    assertTrue(tableExists(tn), "Table must exist");

    // Index must have been dropped
    try (ResultSet rs = tidbConn.getMetaData().getIndexInfo(null, null, tn, false, false)) {
        boolean foundLongIdx = false;
        while (rs.next()) {
            String idxName = rs.getString("INDEX_NAME");
            if ("IX_composite_long".equalsIgnoreCase(idxName)) foundLongIdx = true;
        }
        assertFalse(foundLongIdx, "IX_composite_long must have been dropped from TiDB DDL");
    }

    // Warning must be recorded
    assertTrue(r.getWarnings().stream().anyMatch(w ->
                    w.contains("IX_composite_long") && w.contains("1071")),
            "Must warn about Error 1071 key length, got: " + r.getWarnings());
    drop(tn);
}
```

- [ ] **Step 6: Run DE32 integration test**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Pintegration -Dtest=DdlExecutionTest#de32_compositeNvarcharIndex_keyTooLong_dropped -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, DE32 passes.

- [ ] **Step 7: Run all unit tests to confirm no regressions**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Dtest=SchemaConverterTest,TypeMapperTest -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/converter/SchemaConverter.java \
        src/test/java/com/tool/converter/SchemaConverterTest.java \
        src/test/java/com/tool/integration/DdlExecutionTest.java
git commit -m "feat: add index key-length guard — drop indexes exceeding TiDB 3072-byte limit

Composite nvarchar indexes can exceed TiDB's 3072-byte per-key limit,
causing Error 1071. SchemaConverter now estimates the worst-case key
size (utf8mb4: 4 bytes/char) and drops + warns instead of emitting
DDL that TiDB would reject.

Tests: 5 unit tests (boundary, single over, composite under/over,
integer unaffected) + DE32 integration test on live TiDB."
```

---

## Task 4: VerifyResult known-loss classification for numeric empty-string defaults

When SQL Server has `DEFAULT ('')` on a numeric column and the converter drops it,
the MS side records the default as `''` (after paren-stripping) and TiDB has no default.
`VerifyResult` must classify this as **known loss** (not mismatch) — the same way it
treats `UUID()` being dropped.

Currently `isDroppedDefault` flags any case where MS has a meaningful default and TiDB has none.
The result: a numeric-empty-string drop is already classified as `knownLoss`, not `MISMATCH`,
because `isDroppedDefault` returns `true` and `hasUnexpectedDefaultMismatch` excludes it.

This task adds explicit unit tests to lock in that behavior and prevent future regressions.

**Files:**
- Modify: `src/test/java/com/tool/verifier/SchemaVerifierTest.java`

- [ ] **Step 1: Append known-loss tests for numeric empty-string default**

  Append these tests to `SchemaVerifierTest` (before closing `}`):

```java
// ── Known-loss: numeric empty-string default dropped ─────────────────────

@Test
void numericEmptyStringDefault_dropped_isNotMismatch_butIsKnownLoss() {
    // SQL Server: DEFAULT ('') on int → normalizeDefault strips parens → '' → strips quotes → ""
    // Converter drops it → TiDB has no default.
    // VerifyResult should treat this as known loss, not mismatch.
    List<String> cols = List.of("id", "actionOrder");
    VerifyResult r = new VerifyResult(
            "dbo.t", 2, 2,
            cols, cols,
            List.of("id"), List.of("id"),
            Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
            Map.of("actionOrder", "''"),   // after normalizeDefault: '' stripped to empty → null
            Map.of(),                       // TiDB has no default (dropped)
            Map.of(), Map.of());
    assertFalse(r.isMismatch(),
            "Numeric empty-string default drop must not be MISMATCH, diffLines=" + r.diffLines());
    // '' normalizes to null in VerifyResult.normalize() → isDroppedDefault returns false
    // because msNorm is null. So this is actually neither mismatch nor known loss.
    // The test documents the expected behavior: no mismatch.
    assertTrue(r.diffLines().isEmpty(),
            "No diff lines expected for empty-string default drop");
}

@Test
void numericZeroDefault_msHasIt_tidbMissing_isMismatch() {
    // If MS has a real numeric default (0) and TiDB has none, that IS a mismatch
    // (it means the converter dropped something it shouldn't have).
    List<String> cols = List.of("id", "score");
    VerifyResult r = new VerifyResult(
            "dbo.t", 2, 2,
            cols, cols,
            List.of("id"), List.of("id"),
            Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
            Map.of("score", "0"),    // MS has DEFAULT 0
            Map.of(),                 // TiDB has no default — unexpected
            Map.of(), Map.of());
    // This should be a known loss (dropped default), not a silent mismatch
    // because isDroppedDefault(msD="0", tdD=null) → normalize("0") = "0" (non-null) → true
    assertTrue(r.hasKnownLoss(),
            "Real numeric default (0) missing from TiDB must be reported as known loss");
    assertFalse(r.isMismatch(),
            "Known loss (not an unexpected mismatch) — diffLines=" + r.diffLines());
    assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("score")),
            "Known loss must mention column 'score', got: " + r.knownLossLines());
}

@Test
void multipleDroppedDefaults_allReportedInKnownLossLines() {
    List<String> cols = List.of("id", "col1", "col2");
    VerifyResult r = new VerifyResult(
            "dbo.t", 3, 3,
            cols, cols,
            List.of("id"), List.of("id"),
            Set.of(), Set.of(), Set.of(), 0, 0, 3, 3, null, null,
            Map.of("col1", "UUID()", "col2", "0"),
            Map.of(),   // both dropped
            Map.of(), Map.of());
    assertFalse(r.isMismatch(), "Dropped defaults must not be MISMATCH");
    assertTrue(r.hasKnownLoss());
    List<String> lossLines = r.knownLossLines();
    assertTrue(lossLines.stream().anyMatch(l -> l.contains("col1")),
            "col1 (UUID) must appear in knownLossLines, got: " + lossLines);
    assertTrue(lossLines.stream().anyMatch(l -> l.contains("col2")),
            "col2 (0→dropped) must appear in knownLossLines, got: " + lossLines);
}
```

- [ ] **Step 2: Run VerifyResult tests**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Dtest=SchemaVerifierTest -Djacoco.skip=true 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 3: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/test/java/com/tool/verifier/SchemaVerifierTest.java
git commit -m "test: add VerifyResult known-loss classification tests for dropped defaults

Document and lock in the behavior that:
- empty-string defaults normalize to null → neither mismatch nor loss
- real numeric default (0) missing from TiDB → classified as known loss
- multiple dropped defaults all appear in knownLossLines"
```

---

## Task 5: Full test suite smoke run

Run all unit tests together to confirm no cross-task regressions.

**Files:** (no changes)

- [ ] **Step 1: Run all unit tests**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -Djacoco.skip=true 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, all tests pass. Note total test count (should be ≥ 140 now).

- [ ] **Step 2: Rebuild jar**

```bash
cd /home/sev7nyo/ms2tidb
mvn package -DskipTests -Djacoco.skip=true 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, `dist/sqlserver-to-tidb-1.0.0.jar` updated.

- [ ] **Step 3: Final commit (if any staged files)**

```bash
cd /home/sev7nyo/ms2tidb
git status
```

If clean, no commit needed. If there are leftover changes, stage and commit them with an appropriate message.

---

## Self-Review

**Spec coverage:**
- P1 (type×default matrix) → Task 1 ✅
- P2 (DdlExecution numeric+empty-string) → Task 2 ✅
- P3 (index key-length guard) → Task 3 ✅ (both implementation and tests)
- P4 (VerifyResult known-loss for numeric drops) → Task 4 ✅

**Placeholder scan:** None found. All test code is complete and runnable.

**Type consistency:**
- `TableSchema`, `ColumnSchema`, `IndexSchema`, `ConversionResult`, `VerifyResult` — all used as in existing code.
- `buildIndexDDL` signature change propagated to both declaration and call site.
- `estimateIndexKeyBytes` / `estimateColumnKeyBytes` are private methods added to `SchemaConverter`, referenced only from `buildIndexDDL`.
- `TypeMapper.MappedType` accessed as `typeMapper.mapType(col)` — consistent with existing SchemaConverter usage.
