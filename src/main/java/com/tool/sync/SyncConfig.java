package com.tool.sync;

public record SyncConfig(
        String metaFile,
        String offsetStoragePath,
        String schemaHistoryPath,
        int pollIntervalMs,
        String snapshotMode
) {

    public static final String DEFAULT_META_FILE = "snapshot-meta.json";
    public static final String DEFAULT_OFFSET_STORAGE_PATH = "snapshot-offsets";
    public static final String DEFAULT_SCHEMA_HISTORY_PATH = "snapshot-schema-history";
    public static final int DEFAULT_POLL_INTERVAL_MS = 500;

    public static SyncConfig defaults() {
        return new SyncConfig(DEFAULT_META_FILE, DEFAULT_OFFSET_STORAGE_PATH,
                DEFAULT_SCHEMA_HISTORY_PATH, DEFAULT_POLL_INTERVAL_MS, null);
    }

    public SyncConfig withMetaFile(String v) { return new SyncConfig(v, offsetStoragePath, schemaHistoryPath, pollIntervalMs, snapshotMode); }
    public SyncConfig withOffsetStoragePath(String v) { return new SyncConfig(metaFile, v, schemaHistoryPath, pollIntervalMs, snapshotMode); }
    public SyncConfig withSchemaHistoryPath(String v) { return new SyncConfig(metaFile, offsetStoragePath, v, pollIntervalMs, snapshotMode); }
    public SyncConfig withPollIntervalMs(int v) { return new SyncConfig(metaFile, offsetStoragePath, schemaHistoryPath, v, snapshotMode); }
    public SyncConfig withSnapshotMode(String v) { return new SyncConfig(metaFile, offsetStoragePath, schemaHistoryPath, pollIntervalMs, v); }
}
