# DataComparator 数据比对接口设计

> 日期: 2026-05-03 | 状态: 已确认

## 目标

将数据比对能力抽象为可复用接口，供 dump 验证、integration test、CLI verify 命令等场景调用。

## 接口

```java
package com.tool.compare;

public interface DataComparator {
    ComparisonReport compare(Connection source, Connection target, ComparisonConfig config);
}
```

完全基于 JDBC `Connection`，不依赖 SourceDriver 或 AppConfig，任意 JDBC 数据源均可用。

## 配置

```java
public record ComparisonConfig(
    String catalog,              // source 库名; target 同名则传 null
    String targetCatalog,        // target 库名不同时指定
    List<String[]> tables,       // [schema, table] 列表; 空=所有用户表
    int batchSize,               // 每批读取行数 (默认 5000)
    int maxMismatchRows,         // 每表差异详情上限 (默认 50)
    boolean stopOnFirst          // 遇错即停 或 收集全部
) {
    public ComparisonConfig {
        if (catalog == null || catalog.isBlank()) throw new IllegalArgumentException("catalog required");
        if (batchSize <= 0) batchSize = 5000;
        if (maxMismatchRows < 0) maxMismatchRows = 50;
    }

    public static ComparisonConfig defaults(String catalog) {
        return new ComparisonConfig(catalog, null, List.of(), 5000, 50, false);
    }
}
```

## 结果模型

```
ComparisonReport
├── Summary: totalTables, matchedTables, mismatchedTables, skippedTables,
│             totalRowsSrc, totalRowsTgt
└── List<TableComparison>
    ├── fullName: "dbo.orders"
    ├── status: MATCHED / MISMATCHED / SKIPPED
    ├── rowCountSrc
    ├── rowCountTgt
    ├── missingInTarget: List<String>    -- source 有 target 无的 PK
    ├── extraInTarget: List<String>      -- target 有 source 无的 PK
    └── List<ColumnDiff>                 -- PK 对齐但值不同的列 (最多 maxMismatchRows 条)
        ├── pkValues: Map<String, String>
        └── diffs: List<Diff> { column, srcValue, tgtValue }
```

## 比对流程

1. **表发现** — 获取 source 表列表；target 查找同名表，不存在记 SKIPPED
2. **主键发现** — JDBC `getPrimaryKeys()` 两边各取，不一致记 warning
3. **行数比对** — `SELECT COUNT(*)` 两边
4. **流式比对** — `SELECT * FROM [table] ORDER BY pk1, pk2` 两边各开 stream，按 batchSize 块读，PK 对齐后逐列比较
5. **值归一化** — 比对前做归一化，避免同义不同表示触发假阳性：
   - `Timestamp`: 精度对齐，`java.sql.Timestamp` vs `java.time.LocalDateTime` 统一
   - `BigDecimal`: 尾数零统一 (`1.00` ≡ `1.0`)
   - 字符串: 末尾空格处理，`\r\n` vs `\n` 统一
   - `NULL` vs 空字符串: 区分不混淆
6. **汇总** — 全部表比对完成后返回 `ComparisonReport`

## 典型用法

```java
// 集成测试 - 验证 dump 后数据一致
DataComparator c = new JdbcDataComparator();
ComparisonReport r = c.compare(srcConn, tidbConn,
    new ComparisonConfig("FinanceDB", null,
        List.of(new String[]{"dbo", "order_items"}), 5000, 50, false));

assertThat(r.hasMismatches()).isFalse();
```

```java
// CLI verify 命令 - 全库比对，放宽详情上限
ComparisonReport r = comparator.compare(src, tgt,
    new ComparisonConfig("FinanceDB", null, List.of(), 5000, 100, false));

for (TableComparison t : r.mismatched()) {
    log.error("{}: src={} tgt={} missing={} extra={}",
        t.fullName(), t.rowCountSrc(), t.rowCountTgt(),
        t.missingInTarget().size(), t.extraInTarget().size());
}
```

## 实现

- `JdbcDataComparator` — 单实现，基于 JDBC metadata API，与数据库无关
- 包路径: `com.tool.compare`
- 旧 `CompareData.java` 用 `JdbcDataComparator` 重写，作为 CLI 调用入口

## 不作为

- 不处理无主键表 (按全列去重后再 MD5 也可做，但先限定 PK 表，非 PK 表 SKIP + warning)
- 不对齐 DDL (归 SchemaVerifier)
- 不自动修复差异
