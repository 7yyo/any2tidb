package com.tool.snapshot.model;

public record SnapshotTableResult(
        String dbName,
        String schema,
        String table,
        long rows,
        long elapsedMs,
        String error
) {
    public boolean isError() { return error != null; }
}
