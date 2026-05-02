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
