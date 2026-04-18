# Schema Checksum Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在所有表迁移完成后，统一输出一张 schema 对比表，每张表一行，显示 SQL Server 与 TiDB 的 7 项指标对比，帮助用户快速确认迁移结果正确性。

**Architecture:** 新增 `SchemaVerifier` 组件，在 `App.java` 迁移循环结束后被调用，读取两侧实际 schema 并对比，以对齐表格形式输出结果。

**Tech Stack:** Java 17, JDBC (`DatabaseMetaData` + SQL Server `sys.*` 系统表), Spring Boot 3.2.5

---

## 对比维度

| 字段 | 说明 | 是否参与 MISMATCH 判定 |
|------|------|----------------------|
| COLS | 列个数 | ✅ |
| 列名集合 | 两边列名是否完全一致 | ✅（体现在 missing cols 差异行） |
| PK | 主键列名列表（按顺序） | ✅ |
| IDX | 非主键索引个数 | ✅ |
| FK(MS) | SQL Server 侧外键个数，TiDB 固定 0 | ❌（仅展示，不算 MISMATCH） |
| NOTNULL | NOT NULL 列个数 | ✅ |
| AI | AUTO_INCREMENT 列名 | ✅ |

任意一项（FK 除外）MS 值 ≠ TiDB 值，该表标记为 MISMATCH。

---

## 数据来源

### SQL Server 侧（通过已有的 `ssConn`）

| 指标 | 查询 |
|------|------|
| COLS | `SELECT COUNT(*) FROM sys.columns c JOIN sys.objects o ON c.object_id=o.object_id WHERE o.name=? AND o.schema_id=SCHEMA_ID(?)` |
| 列名集合 | 同上，SELECT c.name |
| PK 列 | `SELECT c.name FROM sys.index_columns ic JOIN sys.columns c ON ic.object_id=c.object_id AND ic.column_id=c.column_id JOIN sys.indexes i ON ic.object_id=i.object_id AND ic.index_id=i.index_id WHERE i.is_primary_key=1 AND OBJECT_NAME(i.object_id)=? ORDER BY ic.key_ordinal` |
| IDX | `SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID(?) AND is_primary_key=0 AND type>0` |
| FK | `SELECT COUNT(*) FROM sys.foreign_keys WHERE parent_object_id=OBJECT_ID(?)` |
| NOTNULL | `SELECT COUNT(*) FROM sys.columns WHERE object_id=OBJECT_ID(?) AND is_nullable=0` |
| AI 列 | `SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(?) AND is_identity=1` |

### TiDB 侧（通过 JDBC `DatabaseMetaData`）

| 指标 | API |
|------|-----|
| COLS | `getColumns(null, null, tableName, null)` → COUNT |
| 列名集合 | 同上 → COLUMN_NAME |
| PK 列 | `getPrimaryKeys(null, null, tableName)` → COLUMN_NAME，按 KEY_SEQ 排序 |
| IDX | `getIndexInfo(null, null, tableName, false, false)` → 去重非 PRIMARY 的 INDEX_NAME COUNT |
| NOTNULL | `getColumns()` → NULLABLE == DatabaseMetaData.columnNoNulls COUNT |
| AI 列 | `getColumns()` → IS_AUTOINCREMENT == "YES" 的 COLUMN_NAME |

---

## 输出格式

迁移汇总行之后，打印 verify 表格：

```
[VERIFY] TABLE                  STATUS    COLS    PK      IDX     FK(MS)  NOTNULL  AI
[VERIFY] dbo.users              OK        12/12   1/1     3/3     2/0     5/5      1/1
[VERIFY] dbo.orders             MISMATCH  8/7     2/2     3/3     0/0     4/4      1/1
         └─ missing cols: [rowguid]
[VERIFY] dbo.order_items        OK        6/6     2/2     1/1     2/0     3/3      0/0
```

- 表头固定一行
- 每张表一行，列宽按最长表名动态对齐
- MISMATCH 的表下方缩进一行，列出所有差异（missing cols、pk mismatch、ai mismatch 等）
- dry-run 模式跳过 verify（无 TiDB 连接）

### 差异描述规则

| 不一致项 | 差异行内容 |
|---------|----------|
| 列个数不同 | `missing cols: [col1, col2]`（MS 有但 TiDB 无）或 `extra cols: [col1]`（TiDB 有但 MS 无） |
| PK 不同 | `pk mismatch: MS=[id,tenant] TiDB=[id]` |
| AI 列不同 | `ai mismatch: MS=id TiDB=(none)` |
| IDX 个数不同 | 通过数字已可见，无需额外行 |
| NOTNULL 个数不同 | 通过数字已可见，无需额外行 |

---

## 新文件

- `src/main/java/com/tool/verifier/SchemaVerifier.java` — 核心校验逻辑，Spring `@Component`
- `src/main/java/com/tool/verifier/VerifyResult.java` — 单张表的校验结果数据类（record）
- `src/test/java/com/tool/verifier/SchemaVerifierTest.java` — 单元测试（mock Connection）
- `src/test/java/com/tool/integration/VerifyIntegrationTest.java` — 集成测试

## 修改文件

- `src/main/java/com/tool/App.java` — 迁移循环结束后调用 `SchemaVerifier`，打印表格

---

## 约束

- 不对比列类型，只对比列名、个数等结构信息
- FK 不算 MISMATCH，仅展示 MS 侧数量
- dry-run 模式不执行 verify
- `SchemaVerifier` 不依赖 `SqlServerExtractor`，直接用 SQL 查询（避免循环依赖，保持职责清晰）
