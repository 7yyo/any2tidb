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
