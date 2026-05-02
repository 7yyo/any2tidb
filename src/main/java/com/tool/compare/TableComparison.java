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
