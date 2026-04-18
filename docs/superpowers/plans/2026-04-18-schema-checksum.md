# Schema Checksum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在所有表迁移完成后统一输出 schema 对比表格，逐表对比 SQL Server 与 TiDB 的列数、列名、主键、索引数、外键数、NOT NULL 数、AUTO_INCREMENT 列，不一致时标记 MISMATCH 并列出差异。

**Architecture:** 新增 `VerifyResult` record 存储单表校验结果，`SchemaVerifier` 组件负责查询两侧数据并对比，`App.java` 在迁移循环结束后调用 verifier 并打印对齐表格。

**Tech Stack:** Java 17 records, JDBC `DatabaseMetaData`, SQL Server `sys.*` 系统表, Spring Boot 3.2.5 `@Component`

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/com/tool/verifier/VerifyResult.java` | 新增 | 单表校验结果数据类（record） |
| `src/main/java/com/tool/verifier/SchemaVerifier.java` | 新增 | 查询两侧 schema、对比、返回 VerifyResult 列表 |
| `src/main/java/com/tool/App.java` | 修改 | 迁移循环后调用 verifier、打印表格 |
| `src/test/java/com/tool/verifier/SchemaVerifierTest.java` | 新增 | 单元测试（mock Connection） |
| `src/test/java/com/tool/integration/VerifyIntegrationTest.java` | 新增 | 集成测试 |

---

### Task 1: VerifyResult record

**Files:**
- Create: `src/main/java/com/tool/verifier/VerifyResult.java`

- [ ] **Step 1: 创建 `VerifyResult.java`**

```java
package com.tool.verifier;

import java.util.List;
import java.util.Set;

/**
 * 单张表的 schema 校验结果。
 * msXxx = SQL Server 侧值，tidbXxx = TiDB 侧值。
 * fk 只展示 MS 侧数量，不参与 MISMATCH 判定。
 */
public record VerifyResult(
        String fullTableName,         // e.g. "dbo.users"
        int msCols,
        int tidbCols,
        Set<String> msColNames,
        Set<String> tidbColNames,
        List<String> msPkCols,        // 按 key_ordinal 排序
        List<String> tidbPkCols,      // 按 KEY_SEQ 排序
        int msIdx,
        int tidbIdx,
        int msFk,                     // TiDB 侧固定 0，不存在 tidbFk 字段
        int msNotNull,
        int tidbNotNull,
        String msAiCol,               // null 表示无 AI 列
        String tidbAiCol              // null 表示无 AI 列
) {
    /** 任意非 FK 指标不一致则返回 true */
    public boolean isMismatch() {
        return msCols != tidbCols
                || !msColNames.equals(tidbColNames)
                || !msPkCols.equals(tidbPkCols)
                || msIdx != tidbIdx
                || msNotNull != tidbNotNull
                || !java.util.Objects.equals(msAiCol, tidbAiCol);
    }

    /** 返回所有差异描述，供打印使用 */
    public List<String> diffLines() {
        List<String> lines = new java.util.ArrayList<>();

        if (msCols != tidbCols || !msColNames.equals(tidbColNames)) {
            Set<String> missing = new java.util.LinkedHashSet<>(msColNames);
            missing.removeAll(tidbColNames);
            Set<String> extra = new java.util.LinkedHashSet<>(tidbColNames);
            extra.removeAll(msColNames);
            if (!missing.isEmpty()) lines.add("missing cols: " + missing);
            if (!extra.isEmpty())   lines.add("extra cols: " + extra);
        }
        if (!msPkCols.equals(tidbPkCols)) {
            lines.add("pk mismatch: MS=" + msPkCols + " TiDB=" + tidbPkCols);
        }
        if (msIdx != tidbIdx) {
            lines.add("idx mismatch: MS=" + msIdx + " TiDB=" + tidbIdx);
        }
        if (msNotNull != tidbNotNull) {
            lines.add("notnull mismatch: MS=" + msNotNull + " TiDB=" + tidbNotNull);
        }
        if (!java.util.Objects.equals(msAiCol, tidbAiCol)) {
            lines.add("ai mismatch: MS=" + (msAiCol != null ? msAiCol : "(none)")
                    + " TiDB=" + (tidbAiCol != null ? tidbAiCol : "(none)"));
        }
        return lines;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd /home/sev7nyo/ms2tidb && mvn compile -q
```

Expected: 无错误输出

- [ ] **Step 3: commit**

```bash
git add src/main/java/com/tool/verifier/VerifyResult.java
git commit -m "feat: add VerifyResult record for schema checksum"
```

---

### Task 2: SchemaVerifier — 单元测试先行

**Files:**
- Create: `src/test/java/com/tool/verifier/SchemaVerifierTest.java`

背景：`SchemaVerifier` 将有一个核心方法：

```java
public VerifyResult verify(Connection msConn, Connection tidbConn, String schemaName, String tableName)
```

单元测试用真实的 SQL Server + TiDB 连接会太重，但 `SchemaVerifier` 内部查询逻辑依赖真实 JDBC，mock 代价也高。因此单元测试只测 `VerifyResult` 的逻辑（`isMismatch()`、`diffLines()`），集成测试覆盖 `SchemaVerifier` 整体。

- [ ] **Step 1: 写单元测试**

```java
package com.tool.verifier;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVerifierTest {

    private VerifyResult ok() {
        return new VerifyResult(
                "dbo.users",
                5, 5,
                Set.of("id", "name", "email", "age", "flag"),
                Set.of("id", "name", "email", "age", "flag"),
                List.of("id"),
                List.of("id"),
                2, 2,
                1,          // msFk — not part of mismatch
                3, 3,
                "id", "id"
        );
    }

    @Test
    void okResult_isNotMismatch() {
        assertFalse(ok().isMismatch());
    }

    @Test
    void okResult_diffLinesIsEmpty() {
        assertTrue(ok().diffLines().isEmpty());
    }

    @Test
    void colCountDiff_isMismatch() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 5, 4,
                Set.of("id","a","b","c","d"), Set.of("id","a","b","c"),
                List.of("id"), List.of("id"),
                1, 1, 0, 3, 3, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols")));
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("d")));
    }

    @Test
    void extraColInTidb_showsInDiffLines() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 4,
                Set.of("id","a","b"), Set.of("id","a","b","extra"),
                List.of("id"), List.of("id"),
                1, 1, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("extra cols")));
    }

    @Test
    void pkMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a","b"), List.of("a"),
                1, 1, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("pk mismatch")));
    }

    @Test
    void idxMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                3, 2, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx mismatch")));
    }

    @Test
    void notNullMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1, 0, 3, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("notnull mismatch")));
    }

    @Test
    void aiMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1, 0, 2, 2, "id", null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("ai mismatch")));
    }

    @Test
    void fkDiff_isNotMismatch() {
        // fk 不参与 mismatch 判定
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1,
                3,   // msFk=3 — should NOT cause mismatch
                2, 2, null, null);
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（SchemaVerifier 还没写）**

```bash
cd /home/sev7nyo/ms2tidb && mvn test -pl . -Dtest=SchemaVerifierTest 2>&1 | grep -E "ERROR|FAIL|Tests run"
```

Expected: 编译失败或 0 tests run（因为 SchemaVerifier 还不存在，但 VerifyResult 已存在，测试本身应该能编译并全部通过）

> 注意：这些测试只测 `VerifyResult`，不依赖 `SchemaVerifier`，所以应该直接通过。如果全部 PASS 也是正确的。

- [ ] **Step 3: commit**

```bash
git add src/test/java/com/tool/verifier/SchemaVerifierTest.java
git commit -m "test: add VerifyResult unit tests"
```

---

### Task 3: SchemaVerifier 实现

**Files:**
- Create: `src/main/java/com/tool/verifier/SchemaVerifier.java`

- [ ] **Step 1: 创建 `SchemaVerifier.java`**

```java
package com.tool.verifier;

import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class SchemaVerifier {

    /**
     * 对比单张表在 SQL Server 和 TiDB 中的 schema 指标。
     *
     * @param msConn    SQL Server 连接
     * @param tidbConn  TiDB 连接
     * @param schema    SQL Server schema 名，如 "dbo"
     * @param tableName 表名
     */
    public VerifyResult verify(Connection msConn, Connection tidbConn,
                               String schema, String tableName) throws SQLException {
        String fullName = schema + "." + tableName;
        String objectId = schema + "." + tableName;  // for OBJECT_ID(?)

        // ---- SQL Server 侧 ----
        int msCols = 0;
        Set<String> msColNames = new LinkedHashSet<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name FROM sys.columns c " +
                "JOIN sys.objects o ON c.object_id = o.object_id " +
                "WHERE o.name = ? AND o.schema_id = SCHEMA_ID(?) " +
                "ORDER BY c.column_id")) {
            ps.setString(1, tableName);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    msColNames.add(rs.getString(1));
                    msCols++;
                }
            }
        }

        List<String> msPkCols = new ArrayList<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name FROM sys.index_columns ic " +
                "JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id " +
                "JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id " +
                "WHERE i.is_primary_key = 1 " +
                "  AND i.object_id = OBJECT_ID(?) " +
                "ORDER BY ic.key_ordinal")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) msPkCols.add(rs.getString(1));
            }
        }

        int msIdx = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.indexes " +
                "WHERE object_id = OBJECT_ID(?) AND is_primary_key = 0 AND type > 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msIdx = rs.getInt(1);
            }
        }

        int msFk = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID(?)")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msFk = rs.getInt(1);
            }
        }

        int msNotNull = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.columns " +
                "WHERE object_id = OBJECT_ID(?) AND is_nullable = 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msNotNull = rs.getInt(1);
            }
        }

        String msAiCol = null;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT name FROM sys.columns WHERE object_id = OBJECT_ID(?) AND is_identity = 1")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msAiCol = rs.getString(1);
            }
        }

        // ---- TiDB 侧（JDBC DatabaseMetaData） ----
        DatabaseMetaData meta = tidbConn.getMetaData();

        int tidbCols = 0;
        Set<String> tidbColNames = new LinkedHashSet<>();
        int tidbNotNull = 0;
        String tidbAiCol = null;
        try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                tidbCols++;
                tidbColNames.add(rs.getString("COLUMN_NAME"));
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) tidbNotNull++;
                if ("YES".equals(rs.getString("IS_AUTOINCREMENT"))) tidbAiCol = rs.getString("COLUMN_NAME");
            }
        }

        List<String> tidbPkCols = new ArrayList<>();
        // getPrimaryKeys 结果无序，需按 KEY_SEQ 排序
        Map<Short, String> pkMap = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        tidbPkCols.addAll(pkMap.values());

        int tidbIdx = 0;
        Set<String> idxNames = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && !name.equalsIgnoreCase("PRIMARY")) idxNames.add(name);
            }
        }
        tidbIdx = idxNames.size();

        return new VerifyResult(
                fullName,
                msCols, tidbCols,
                msColNames, tidbColNames,
                msPkCols, tidbPkCols,
                msIdx, tidbIdx,
                msFk,
                msNotNull, tidbNotNull,
                msAiCol, tidbAiCol
        );
    }

    /**
     * 批量校验，返回每张表的 VerifyResult。
     * tableList 格式与 App.java 中相同：String[]{schemaName, tableName}
     */
    public List<VerifyResult> verifyAll(Connection msConn, Connection tidbConn,
                                        List<String[]> tableList) throws SQLException {
        List<VerifyResult> results = new ArrayList<>();
        for (String[] entry : tableList) {
            results.add(verify(msConn, tidbConn, entry[0], entry[1]));
        }
        return results;
    }
}
```

- [ ] **Step 2: 编译**

```bash
cd /home/sev7nyo/ms2tidb && mvn compile -q
```

Expected: 无错误输出

- [ ] **Step 3: 运行单元测试**

```bash
mvn test -Dtest=SchemaVerifierTest 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

Expected:
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 4: commit**

```bash
git add src/main/java/com/tool/verifier/SchemaVerifier.java
git commit -m "feat: add SchemaVerifier component"
```

---

### Task 4: App.java — 迁移后打印 verify 表格

**Files:**
- Modify: `src/main/java/com/tool/App.java`

当前 `App.java` 迁移循环结束后打印汇总：
```java
log("INFO", "Conversion completed: " + succeeded + " ...");
```

在这一行之后调用 verifier 并打印表格。同时需要在迁移循环期间记录成功迁移的表列表（ERROR 的表不参与 verify）。

- [ ] **Step 1: 修改 `App.java`**

在 `App` 构造函数中注入 `SchemaVerifier`，迁移循环中收集成功表，循环后调用 `verifyAll` 并打印表格。

完整的 `run` 方法替换如下（只展示变更部分，构造函数也需更新）：

```java
// 构造函数（替换原有构造函数）
public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter,
           TiDBWriter writer, SchemaVerifier verifier) {
    this.config = config;
    this.extractor = extractor;
    this.converter = converter;
    this.writer = writer;
    this.verifier = verifier;
}

// 新增字段
private final SchemaVerifier verifier;
```

`run` 方法内，在 `List<String[]> tableList` 声明后新增：
```java
List<String[]> succeededTables = new ArrayList<>();
```

在迁移循环的 `case OK` 分支中，追加记录：
```java
case OK -> {
    log("INFO", progress + " Converting table " + fullName + " ... OK");
    succeeded++;
    succeededTables.add(entry);
}
```

`WARN` 分支同样记录（WARN 说明表已成功创建，只是有警告）：
```java
case WARN -> {
    for (String w : result.getWarnings()) log("WARN", progress + " Converting table " + fullName + " ... " + w);
    warned++;
    succeededTables.add(entry);
}
```

在汇总日志行之后，添加 verify 调用：
```java
log("INFO", "Conversion completed: " + succeeded + " succeeded, " + warned + " warnings, " + failed + " failed");

// Schema verify（dry-run 时跳过，因为没有 tidbConn）
if (!dryRun && tidbConn != null && !succeededTables.isEmpty()) {
    printVerifyTable(tidbConn, ssConn, succeededTables);
}
```

新增 `printVerifyTable` 私有方法（加在 `log` 方法之前）：

```java
private void printVerifyTable(Connection tidbConn, Connection msConn, List<String[]> tables) {
    List<VerifyResult> results;
    try {
        results = verifier.verifyAll(msConn, tidbConn, tables);
    } catch (Exception e) {
        log("ERROR", "[VERIFY] failed to run schema checksum: " + e.getMessage());
        return;
    }

    // 计算表名列宽（最短 25）
    int nameWidth = results.stream()
            .mapToInt(r -> r.fullTableName().length())
            .max().orElse(10);
    nameWidth = Math.max(nameWidth, 25);

    String fmt = "[VERIFY] %-" + nameWidth + "s  %-8s  %-7s  %-7s  %-7s  %-7s  %-7s  %-7s%n";
    String detailIndent = " ".repeat(9 + nameWidth + 2);  // align with STATUS column

    System.out.printf(fmt, "TABLE", "STATUS", "COLS", "PK", "IDX", "FK(MS)", "NOTNULL", "AI");

    for (VerifyResult r : results) {
        String status = r.isMismatch() ? "MISMATCH" : "OK";
        String cols    = r.msCols()    + "/" + r.tidbCols();
        String pk      = r.msPkCols().size() + "/" + r.tidbPkCols().size();
        String idx     = r.msIdx()     + "/" + r.tidbIdx();
        String fk      = r.msFk()      + "/0";
        String notnull = r.msNotNull() + "/" + r.tidbNotNull();
        String ai      = (r.msAiCol() != null ? "1" : "0") + "/" + (r.tidbAiCol() != null ? "1" : "0");

        System.out.printf(fmt, r.fullTableName(), status, cols, pk, idx, fk, notnull, ai);

        if (r.isMismatch()) {
            for (String line : r.diffLines()) {
                System.out.println(detailIndent + "└─ " + line);
            }
        }
    }
}
```

还需在文件顶部 import：
```java
import com.tool.verifier.SchemaVerifier;
import com.tool.verifier.VerifyResult;
```

- [ ] **Step 2: 编译**

```bash
cd /home/sev7nyo/ms2tidb && mvn compile -q
```

Expected: 无错误

- [ ] **Step 3: 运行已有单元测试，确认不破坏现有逻辑**

```bash
mvn test -Dtest="TypeMapperTest,SchemaConverterTest,SchemaVerifierTest" 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

Expected:
```
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 4: commit**

```bash
git add src/main/java/com/tool/App.java
git commit -m "feat: print schema verify table after migration"
```

---

### Task 5: 集成测试

**Files:**
- Create: `src/test/java/com/tool/integration/VerifyIntegrationTest.java`

连接信息与现有 `IntegrationTest.java` 完全相同（SQL Server `127.0.0.1:1433` sa/Test1234!，TiDB `127.0.0.1:4000` root/无密码，database `testdb`）。

- [ ] **Step 1: 创建集成测试**

```java
package com.tool.integration;

import com.tool.converter.SchemaConverter;
import com.tool.converter.TypeMapper;
import com.tool.extractor.SqlServerExtractor;
import com.tool.model.TableSchema;
import com.tool.verifier.SchemaVerifier;
import com.tool.verifier.VerifyResult;
import com.tool.writer.TiDBWriter;
import com.tool.ConversionResult;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SchemaVerifier.
 * Requires SQL Server on 127.0.0.1:1433 (sa/Test1234!) and TiDB on 127.0.0.1:4000 (root/no password).
 * Run with: mvn test -Pintegration -Dtest=VerifyIntegrationTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerifyIntegrationTest {

    private static final String SS_URL =
            "jdbc:sqlserver://127.0.0.1:1433;databaseName=testdb;encrypt=true;trustServerCertificate=true";
    private static final String TIDB_URL =
            "jdbc:mysql://127.0.0.1:4000/testdb?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    private static Connection ssConn;
    private static Connection tidbConn;

    private final SchemaVerifier verifier = new SchemaVerifier();
    private final SqlServerExtractor extractor = new SqlServerExtractor();
    private final SchemaConverter converter = new SchemaConverter(new TypeMapper());
    private final TiDBWriter writer = new TiDBWriter();

    @BeforeAll
    static void connect() throws Exception {
        ssConn = DriverManager.getConnection(SS_URL, "sa", "Test1234!");
        tidbConn = DriverManager.getConnection(TIDB_URL, "root", "");
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (ssConn != null) ssConn.close();
        if (tidbConn != null) tidbConn.close();
    }

    private void executeSS(String sql) throws SQLException {
        try (Statement st = ssConn.createStatement()) { st.execute(sql); }
    }

    private void executeTiDB(String sql) throws SQLException {
        try (Statement st = tidbConn.createStatement()) { st.execute(sql); }
    }

    private void dropSSTable(String name) {
        try { executeSS("IF OBJECT_ID('dbo." + name + "', 'U') IS NOT NULL DROP TABLE dbo." + name); }
        catch (SQLException ignored) {}
    }

    private void dropTiDBTable(String name) {
        try { executeTiDB("DROP TABLE IF EXISTS `" + name + "`"); }
        catch (SQLException ignored) {}
    }

    private void migrate(String tableName) throws Exception {
        TableSchema schema = extractor.extractTable(ssConn, "dbo", tableName);
        ConversionResult result = new ConversionResult("dbo." + tableName);
        String ddl = converter.toCreateTableDDL(schema, result, false);
        writer.executeDDL(tidbConn, ddl, result);
    }

    /**
     * VT01: 完全一致的表 → OK，所有指标 MS值==TiDB值
     */
    @Test
    @Order(1)
    void vt01_cleanTable_isOK() throws Exception {
        String t = "vt_clean";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.vt_clean (
                id   INT NOT NULL IDENTITY(1,1) PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                age  INT
            );
            CREATE INDEX IX_vt_name ON dbo.vt_clean (name);
            """);
        migrate(t);

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", t);
        assertFalse(r.isMismatch(), "全量迁移后不应有 MISMATCH，diffLines=" + r.diffLines());
        assertEquals(3, r.msCols());
        assertEquals(3, r.tidbCols());
        assertEquals(List.of("id"), r.msPkCols());
        assertEquals(List.of("id"), r.tidbPkCols());
        assertEquals(1, r.msIdx());
        assertEquals(1, r.tidbIdx());
        assertEquals(0, r.msFk());
        assertEquals("id", r.msAiCol());
        assertEquals("id", r.tidbAiCol());

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * VT02: 手动在 TiDB 删一列后 → MISMATCH，missing cols 中包含该列
     */
    @Test
    @Order(2)
    void vt02_missingColInTidb_isMismatch() throws Exception {
        String t = "vt_missing_col";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.vt_missing_col (
                id   INT NOT NULL PRIMARY KEY,
                name VARCHAR(50),
                note VARCHAR(200)
            )
            """);
        migrate(t);
        // 模拟 TiDB 侧少一列
        executeTiDB("ALTER TABLE `" + t + "` DROP COLUMN `note`");

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", t);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols") && l.contains("note")));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * VT03: 有外键的表 → FK 显示 MS 侧数量，但不算 MISMATCH
     */
    @Test
    @Order(3)
    void vt03_foreignKey_notMismatch() throws Exception {
        dropSSTable("vt_fk_child"); dropSSTable("vt_fk_parent");
        dropTiDBTable("vt_fk_child"); dropTiDBTable("vt_fk_parent");
        executeSS("""
            CREATE TABLE dbo.vt_fk_parent (id INT NOT NULL PRIMARY KEY);
            CREATE TABLE dbo.vt_fk_child (
                id INT NOT NULL PRIMARY KEY,
                pid INT,
                CONSTRAINT FK_vt FOREIGN KEY (pid) REFERENCES dbo.vt_fk_parent(id)
            );
            """);
        migrate("vt_fk_parent");
        migrate("vt_fk_child");

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", "vt_fk_child");
        assertEquals(1, r.msFk(), "MS 侧应有 1 个外键");
        assertFalse(r.isMismatch(), "外键不参与 MISMATCH 判定");

        dropSSTable("vt_fk_child"); dropSSTable("vt_fk_parent");
        dropTiDBTable("vt_fk_child"); dropTiDBTable("vt_fk_parent");
    }

    /**
     * VT04: verifyAll 批量校验
     */
    @Test
    @Order(4)
    void vt04_verifyAll() throws Exception {
        String t1 = "vt_all_a", t2 = "vt_all_b";
        dropSSTable(t1); dropSSTable(t2);
        dropTiDBTable(t1); dropTiDBTable(t2);
        executeSS("CREATE TABLE dbo.vt_all_a (id INT NOT NULL PRIMARY KEY, val VARCHAR(50))");
        executeSS("CREATE TABLE dbo.vt_all_b (id INT NOT NULL PRIMARY KEY, num BIGINT)");
        migrate(t1); migrate(t2);

        List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn,
                List.of(new String[]{"dbo", t1}, new String[]{"dbo", t2}));
        assertEquals(2, results.size());
        results.forEach(r -> assertFalse(r.isMismatch(), r.fullTableName() + " should be OK"));

        dropSSTable(t1); dropSSTable(t2);
        dropTiDBTable(t1); dropTiDBTable(t2);
    }
}
```

- [ ] **Step 2: 运行集成测试**

```bash
cd /home/sev7nyo/ms2tidb && mvn test -Pintegration -Dtest=VerifyIntegrationTest 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

Expected:
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 运行全部测试**

```bash
mvn test -Pintegration 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

Expected:
```
[INFO] Tests run: 24, Failures: 0, ...
[INFO] Tests run: 7,  Failures: 0, ...
[INFO] Tests run: 8,  Failures: 0, ...
[INFO] Tests run: 12, Failures: 0, ...
[INFO] Tests run: 4,  Failures: 0, ...
[INFO] Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 4: commit**

```bash
git add src/test/java/com/tool/integration/VerifyIntegrationTest.java
git commit -m "test: add SchemaVerifier integration tests"
```
