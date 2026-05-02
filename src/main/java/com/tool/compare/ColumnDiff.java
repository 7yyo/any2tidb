package com.tool.compare;

import java.util.List;
import java.util.Map;

public record ColumnDiff(
    Map<String, String> pkValues,
    List<Diff> diffs
) {
    public record Diff(String column, String srcValue, String tgtValue) {}

    public boolean hasDiff() { return !diffs.isEmpty(); }
}
