package com.tool.snapshot.model;

import java.time.Instant;
import java.util.List;

public record SnapshotDbResult(
        String dbName,
        int tables,
        long rows,
        Instant completedAt,
        String error
) {
    public boolean isError() { return error != null; }

    public static boolean shouldBlockPipeline(List<SnapshotDbResult> results) {
        return results.stream().anyMatch(SnapshotDbResult::isError);
    }
}
