package com.tool.schema.verifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单张表的 schema 校验结果。
 * srcXxx = 源端值，tidbXxx = TiDB 侧值。
 * FK 和 CHECK 均在迁移时丢弃，不参与 MISMATCH 判定。
 */
public record VerifyResult(
        String fullTableName,              // e.g. "dbo.users"
        int srcCols,
        int tidbCols,
        List<String> srcColOrder,          // 按源端列序排序的列名
        List<String> tidbColOrder,         // 按 ORDINAL_POSITION 排序的列名
        List<String> srcPkCols,            // 按 key_ordinal 排序
        List<String> tidbPkCols,           // 按 KEY_SEQ 排序
        Set<String> srcIdxNames,           // 非主键、非丢弃的索引名
        Set<String> srcDroppedIdxNames,    // 迁移时已丢弃的索引名 (COLUMNSTORE / FULLTEXT)
        Set<String> tidbIdxNames,          // TiDB 侧非主键索引名
        int srcChecks,
        int tidbChecks,
        int srcNotNull,
        int tidbNotNull,
        String srcAiCol,                   // null 表示无 AI 列
        String tidbAiCol,                  // null 表示无 AI 列
        Map<String, String> srcDefaults,   // colName → 归一化后的默认值（null 表示无默认值）
        Map<String, String> tidbDefaults,  // colName → TiDB 实际默认值
        Map<String, String> srcColTypes,   // colName (lower) → 源端列类型字符串，如 "nvarchar(max)"
        Map<String, String> tidbColTypes   // colName (lower) → TiDB 列类型字符串，如 "LONGTEXT"
) {
    // ── 兼容旧调用：从有序列名 List 派生出 Set ────────────────────────────────
    public Set<String> srcColNames()  { return new java.util.LinkedHashSet<>(srcColOrder); }
    public Set<String> tidbColNames() { return new java.util.LinkedHashSet<>(tidbColOrder); }

    /**
     * 任意非 FK / 非 CHECK、且不属于"预期内已知 loss"的指标不一致则返回 true。
     * CHECK 不参与判定：TiDB 不强制 CHECK，约束已在迁移时丢弃。
     * 已知 loss（UUID() dropped、UTC_TIMESTAMP 替换等）不算 MISMATCH，
     * 通过 hasKnownLoss() / knownLossLines() 单独报告。
     */
    public boolean isMismatch() {
        return srcCols != tidbCols
                || !srcColOrder.equals(tidbColOrder)
                || !srcPkCols.equals(tidbPkCols)
                || hasUnexpectedIdxMismatch()
                || srcNotNull != tidbNotNull
                || !java.util.Objects.equals(srcAiCol, tidbAiCol)
                || hasUnexpectedDefaultMismatch();
    }

    /**
     * 有需要告知用户的预期内损失（东西被丢掉了或被替换为不等价的值）。
     */
    public boolean hasKnownLoss() {
        return !srcDroppedIdxNames.isEmpty() || hasDroppedDefault() || hasSemanticDefaultReplacement();
    }

    // ── 索引：只比较"保留"索引；丢弃的走 knownLoss ─────────────────────────

    private boolean hasUnexpectedIdxMismatch() {
        // 大小写不敏感比较（TiDB 会将索引名小写存储）
        Set<String> srcLower = srcIdxNames.stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> tidbLower = tidbIdxNames.stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return !srcLower.equals(tidbLower);
    }

    // ── 默认值：区分"已知转换"和"真正不同" ──────────────────────────────────

    private boolean hasUnexpectedDefaultMismatch() {
        for (String col : srcColOrder) {
            String srcD = srcDefaults.getOrDefault(col, null);
            String tdD = tidbDefaults.getOrDefault(col.toLowerCase(), null);
            if (!defaultsEqual(srcD, tdD) && !isKnownConversion(srcD, tdD)
                    && !isDroppedDefault(srcD, tdD) && !isSemanticDefaultReplacement(srcD, tdD)) return true;
        }
        return false;
    }

    /** UUID() 被丢弃（TiDB 侧无默认值）—— 需要报 LOSS。 */
    private boolean hasDroppedDefault() {
        for (String col : srcColOrder) {
            String srcD = srcDefaults.getOrDefault(col, null);
            String tdD = tidbDefaults.getOrDefault(col.toLowerCase(), null);
            if (isDroppedDefault(srcD, tdD)) return true;
        }
        return false;
    }

    /**
     * 返回所有真正差异描述（MISMATCH 用），供打印使用。
     * 不包含已知 loss 行。
     */
    public List<String> diffLines() {
        List<String> lines = new java.util.ArrayList<>();

        // 列名差异（不区分顺序）
        if (srcCols != tidbCols || !srcColNames().equals(tidbColNames())) {
            Set<String> missing = new java.util.LinkedHashSet<>(srcColNames());
            missing.removeAll(tidbColNames());
            Set<String> extra = new java.util.LinkedHashSet<>(tidbColNames());
            extra.removeAll(srcColNames());
            if (!missing.isEmpty()) lines.add("missing cols: " + missing);
            if (!extra.isEmpty())   lines.add("extra cols: " + extra);
        }

        // 列顺序差异
        if (srcColNames().equals(tidbColNames()) && !srcColOrder.equals(tidbColOrder)) {
            for (int i = 0; i < Math.min(srcColOrder.size(), tidbColOrder.size()); i++) {
                if (!srcColOrder.get(i).equalsIgnoreCase(tidbColOrder.get(i))) {
                    lines.add("col order mismatch at pos " + (i + 1)
                            + ": SRC=" + srcColOrder.get(i) + " TiDB=" + tidbColOrder.get(i));
                    break;
                }
            }
        }

        if (!srcPkCols.equals(tidbPkCols)) {
            lines.add("pk mismatch: SRC=" + srcPkCols + " TiDB=" + tidbPkCols);
        }

        // 索引差异（仅保留索引，大小写不敏感）
        if (hasUnexpectedIdxMismatch()) {
            Set<String> tidbLower = tidbIdxNames.stream()
                    .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
            Set<String> srcLower2 = srcIdxNames.stream()
                    .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
            Set<String> missing = srcIdxNames.stream()
                    .filter(n -> !tidbLower.contains(n.toLowerCase()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            Set<String> extra = tidbIdxNames.stream()
                    .filter(n -> !srcLower2.contains(n.toLowerCase()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!missing.isEmpty()) lines.add("idx missing in TiDB: " + missing);
            if (!extra.isEmpty())   lines.add("idx extra in TiDB: " + extra);
        }

        // CHECK not compared — TiDB discards check constraints during migration
        if (srcNotNull != tidbNotNull) {
            lines.add("notnull mismatch: SRC=" + srcNotNull + " TiDB=" + tidbNotNull);
        }
        if (!java.util.Objects.equals(srcAiCol, tidbAiCol)) {
            lines.add("ai mismatch: SRC=" + (srcAiCol != null ? srcAiCol : "(none)")
                    + " TiDB=" + (tidbAiCol != null ? tidbAiCol : "(none)"));
        }

        // 默认值差异（仅预期外）
        for (String col : srcColOrder) {
            String srcD = srcDefaults.getOrDefault(col, null);
            String tdD = tidbDefaults.getOrDefault(col.toLowerCase(), null);
            if (!defaultsEqual(srcD, tdD) && !isDroppedDefault(srcD, tdD)
                    && !isKnownConversion(srcD, tdD) && !isSemanticDefaultReplacement(srcD, tdD)) {
                lines.add(defaultLabel(col) + ": SRC=" + (srcD != null ? srcD : "(none)")
                        + " TiDB=" + (tdD != null ? tdD : "(none)"));
            }
        }

        return lines;
    }

    /**
     * 返回所有预期内差异描述（LOSS 用）。
     */
    public List<String> knownLossLines() {
        List<String> lines = new java.util.ArrayList<>();

        // 已丢弃的索引
        if (!srcDroppedIdxNames.isEmpty()) {
            lines.add("idx dropped (not supported in TiDB): " + srcDroppedIdxNames);
        }

        // 已知默认值丢弃/替换
        for (String col : srcColOrder) {
            String srcD = srcDefaults.getOrDefault(col, null);
            String tdD = tidbDefaults.getOrDefault(col.toLowerCase(), null);
            if (!defaultsEqual(srcD, tdD)) {
                if (isDroppedDefault(srcD, tdD)) {
                    lines.add(defaultLabel(col) + ": SRC=" + (srcD != null ? srcD : "(none)")
                            + " → TiDB=(dropped)");
                } else if (isSemanticDefaultReplacement(srcD, tdD)) {
                    lines.add(defaultLabel(col) + ": SRC=" + srcD
                            + " → TiDB=" + tdD + " (UTC→local time, verify manually)");
                }
            }
        }

        return lines;
    }

    // ── 判断是否属于已知转换导致的差异 ─────────────────────────────────────────

    /** 构建 "default[col] (msType → tidbType)" 或 "default[col]" 标签。 */
    private String defaultLabel(String col) {
        String srcType = srcColTypes.getOrDefault(col.toLowerCase(), null);
        String tdType  = tidbColTypes.getOrDefault(col.toLowerCase(), null);
        if (srcType != null || tdType != null) {
            String hint = (srcType != null ? srcType : "?") + " → " + (tdType != null ? tdType : "?");
            return "default[" + col + "] (" + hint + ")";
        }
        return "default[" + col + "]";
    }

    /**
     * 默认值被真正丢弃（TiDB 侧无对应默认值）——需要报 LOSS。
     * UUID() 会被丢弃；其他无法转换的函数/表达式也会被丢弃。
     */
    private static boolean isDroppedDefault(String srcD, String tdD) {
        if (srcD == null) return false;
        // Normalise first — empty / blank after normalisation means "no default"; not a loss.
        String srcNorm = normalize(srcD);
        if (srcNorm == null) return false;
        // TiDB side must also have no default for this to be a "dropped" case.
        if (tdD != null && !tdD.isBlank()) return false;
        // Anything remaining: source had a meaningful default, TiDB has none → loss.
        return true;
    }

    /**
     * 预期内的静默转换（converter 有意替换，语义等价或已知降级）——既不报 MISMATCH 也不报 LOSS。
     * srcD 是归一化后的源端值，tdD 是 TiDB 实际值。
     */
    private static boolean isKnownConversion(String srcD, String tdD) {
        if (srcD == null) return false;
        String src = srcD.trim().toUpperCase();

        // Source explicit NULL default → TiDB has no default entry (nullable column)
        if ("NULL".equals(src) && (tdD == null || tdD.isBlank())) return true;

        if (tdD == null) return false;
        String td = tdD.trim().toUpperCase();

        // CURRENT_TIMESTAMP → CURRENT_DATE（DATE 列 CURDATE() 在 TiDB 展示为 CURRENT_DATE）
        if ("CURRENT_TIMESTAMP".equals(src) && "CURRENT_DATE".equals(td)) return true;

        return false;
    }

    /** UTC_TIMESTAMP() → CURRENT_TIMESTAMP — 语义差异（UTC 变本地时间），报 NOTE。 */
    private boolean isSemanticDefaultReplacement(String srcD, String tdD) {
        if (srcD == null || tdD == null) return false;
        String src = srcD.trim().toUpperCase();
        String td = tdD.trim().toUpperCase();
        return "UTC_TIMESTAMP()".equals(src) && td.startsWith("CURRENT_TIMESTAMP");
    }

    private boolean hasSemanticDefaultReplacement() {
        for (String col : srcColOrder) {
            String srcD = srcDefaults.getOrDefault(col, null);
            String tdD = tidbDefaults.getOrDefault(col.toLowerCase(), null);
            if (isSemanticDefaultReplacement(srcD, tdD)) return true;
        }
        return false;
    }

    /** 大小写不敏感地比较两个默认值，null 和空字符串视为等价；去掉源端包裹的单引号再比 */
    private static boolean defaultsEqual(String a, String b) {
        // Strip source single-quote wrapping first (e.g. '' → "", 'CN' → CN),
        // then treat null and blank-after-strip as equivalent.
        String na = normalize(a);
        String nb = normalize(b);
        if (na == null && nb == null) return true;
        if (na == null || nb == null) return false;
        if (na.equalsIgnoreCase(nb)) return true;
        // CURRENT_TIMESTAMP and CURRENT_TIMESTAMP(n) are equivalent
        String naBase = na.replaceAll("(?i)^CURRENT_TIMESTAMP\\(\\d+\\)$", "CURRENT_TIMESTAMP");
        String nbBase = nb.replaceAll("(?i)^CURRENT_TIMESTAMP\\(\\d+\\)$", "CURRENT_TIMESTAMP");
        return naBase.equalsIgnoreCase(nbBase);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String v = stripQuotes(s.trim());
        return v.isEmpty() ? null : v;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'"))
            return s.substring(1, s.length() - 1);
        return s;
    }
}
