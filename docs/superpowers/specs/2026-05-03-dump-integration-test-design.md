# Dump 集成测试设计

> 日期: 2026-05-03 | 状态: 已确认

## 目标

用 DataComparator 端到端验证 dump 输出正确性：dump CSV → LOAD DATA 到 TiDB → 比对源和目标。

## 架构

```
DumpIntegrationTest (JUnit 5, @Tag("integration"))
├── @BeforeAll: SQL Server 建测试库 + 专用类型覆盖表 + 插数据
├── @Test: dump(API) → mysql LOAD DATA → DataComparator → assert
└── @AfterAll: 清理 SQL Server 和 TiDB 的测试库
```

- dump 走 Java API 调 DumpStep（调试友好）
- LOAD DATA 走 `mysql` 命令行（覆盖真实加载路径）

## 测试表

### dump_test_types — 类型覆盖表（~20 行）

| 列 | 类型 | 说明 |
|---|---|---|
| id | INT PK | 主键 |
| col_bigint | BIGINT | |
| col_decimal | DECIMAL(18,4) | 精度+尾零 |
| col_float | FLOAT(53) | |
| col_money | MONEY | → DECIMAL(19,4) |
| col_bit | BIT | → TINYINT(1) |
| col_date | DATE | |
| col_time | TIME(0) | fsp=0 边界 |
| col_datetime | DATETIME | |
| col_datetime2_0 | DATETIME2(0) | fsp=0 |
| col_datetime2_7 | DATETIME2(7) | max精度→TiDB截断 |
| col_datetimeoffset | DATETIMEOFFSET(3) | 时区丢失 |
| col_char | CHAR(10) | 定长+尾空格 |
| col_varchar | VARCHAR(50) | |
| col_nvarchar | NVARCHAR(50) | Unicode |
| col_text | TEXT | |
| col_null_str | VARCHAR(20) NULL | NULL 值 |
| col_guid | UNIQUEIDENTIFIER | → VARCHAR(36) |

数据覆盖：正常值、NULL、空字符串 `''`、含 `"` 的值、含 `,` 的值、中文、`\r\n` 换行值。

### dump_test_simple — 数据规模表（~1000 行）

验证多 chunk PK-range 分片正确性。

## 测试流程

### setUp
1. 连 SQL Server，CREATE DATABASE dump_test_db
2. CREATE TABLE dump_test_types / dump_test_simple
3. INSERT 测试数据

### test
1. **dump**: 调 DumpStep，`--databases dump_test_db --output-dir tmp/dump-test`
2. **load**: 生成 `tmp/load.sql`，包含 CREATE TABLE + LOAD DATA 语句，由测试遍历 dump 输出目录自动发现 CSV
3. **mysql 执行**: `Runtime.exec("mysql -h 127.0.0.1 -P 4000 -u root -e 'source tmp/load.sql'")`
4. **compare**: `new JdbcDataComparator().compare(srcConn, tidbConn, ComparisonConfig.defaults("dump_test_db"))`
5. **assert**: `hasMismatches() == false`

### tearDown
- SQL Server: DROP DATABASE dump_test_db
- TiDB: DROP DATABASE dump_test_db
- 清理 tmp/dump-test/

## 边界覆盖

| 场景 | 方式 |
|---|---|
| 行数一致 | DataComparator rowCount |
| 值一致 | DataComparator columnDiffs |
| NULL 正确表达 `\N` | LOAD DATA 后 NULL 不为空串 |
| `\r\n` 换行 | LOAD DATA LINES TERMINATED BY '\r\n' |
| Decimal 精度 | BigDecimal stripTrailingZeros 比对 |
| 类型转换 | SchemaConverter MappedType warnings |
| 多 chunk 分片 | dump_test_simple 1000 行跨多 chunk |

## 不作为

- 不测 snapshot / sync 模式
- 不做性能基准
- 不用 Testcontainers（环境已有 SQL Server + TiDB）
