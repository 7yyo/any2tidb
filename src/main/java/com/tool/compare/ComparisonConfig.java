package com.tool.compare;

import java.util.List;

public record ComparisonConfig(
    String catalog,
    String targetCatalog,
    List<String[]> tables,
    int batchSize,
    int maxMismatchRows
) {
    public ComparisonConfig {
        if (catalog == null || catalog.isBlank()) throw new IllegalArgumentException("catalog required");
        if (batchSize <= 0) batchSize = 5000;
        if (maxMismatchRows < 0) maxMismatchRows = 50;
    }

    public static ComparisonConfig defaults(String catalog) {
        return new ComparisonConfig(catalog, null, List.of(), 5000, 50);
    }
}
