package com.tool.snapshot;

import java.util.List;
import java.util.stream.Collectors;

public record SnapshotConfig(
        String offsetStoragePath,
        String schemaHistoryPath,
        String snapshotMode,
        int batchInsertSize,
        int snapshotFetchSize,
        int maxQueueSize,
        int pollIntervalMs,
        int offsetCommitIntervalMs,
        int snapshotMaxThreads,
        double snapshotMaxThreadsMultiplier
) {

    public static final String DEFAULT_OFFSET_STORAGE_PATH = "snapshot-offsets";
    public static final String DEFAULT_SCHEMA_HISTORY_PATH = "snapshot-schema-history";
    public static final String DEFAULT_SNAPSHOT_MODE = "initial_only";
    public static final int DEFAULT_BATCH_INSERT_SIZE = 10000;
    public static final int DEFAULT_SNAPSHOT_FETCH_SIZE = 10000;
    public static final int DEFAULT_MAX_QUEUE_SIZE = 16384;
    public static final int DEFAULT_POLL_INTERVAL_MS = 500;
    public static final int DEFAULT_OFFSET_COMMIT_INTERVAL_MS = 10000;
    public static final int DEFAULT_SNAPSHOT_MAX_THREADS = 1;
    public static final double DEFAULT_SNAPSHOT_MAX_THREADS_MULTIPLIER = 1.0;

    public static SnapshotConfig defaults() {
        return new SnapshotConfig(
                DEFAULT_OFFSET_STORAGE_PATH,
                DEFAULT_SCHEMA_HISTORY_PATH,
                DEFAULT_SNAPSHOT_MODE,
                DEFAULT_BATCH_INSERT_SIZE,
                DEFAULT_SNAPSHOT_FETCH_SIZE,
                DEFAULT_MAX_QUEUE_SIZE,
                DEFAULT_POLL_INTERVAL_MS,
                DEFAULT_OFFSET_COMMIT_INTERVAL_MS,
                DEFAULT_SNAPSHOT_MAX_THREADS,
                DEFAULT_SNAPSHOT_MAX_THREADS_MULTIPLIER
        );
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public SnapshotConfig withBatchInsertSize(int batchInsertSize) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSnapshotFetchSize(int snapshotFetchSize) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withOffsetStoragePath(String offsetStoragePath) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSchemaHistoryPath(String schemaHistoryPath) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withMaxQueueSize(int maxQueueSize) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withPollIntervalMs(int pollIntervalMs) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withOffsetCommitIntervalMs(int offsetCommitIntervalMs) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSnapshotMaxThreads(int snapshotMaxThreads) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSnapshotMaxThreadsMultiplier(double snapshotMaxThreadsMultiplier) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    /**
     * Builds a Debezium {@code table.include.list} regex from schema+table pairs.
     * Each entry in {@code tables} is {@code [schemaName, tableName]}.
     * When empty or null, matches all schemas and tables.
     */
    public String buildTableIncludeList(List<String[]> tables) {
        if (tables == null || tables.isEmpty()) {
            return ".*\\..*";
        }
        return tables.stream()
                .map(t -> t[0] + "\\." + t[1])
                .collect(Collectors.joining(","));
    }

    public static class Builder {
        private String offsetStoragePath = DEFAULT_OFFSET_STORAGE_PATH;
        private String schemaHistoryPath = DEFAULT_SCHEMA_HISTORY_PATH;
        private String snapshotMode = DEFAULT_SNAPSHOT_MODE;
        private int batchInsertSize = DEFAULT_BATCH_INSERT_SIZE;
        private int snapshotFetchSize = DEFAULT_SNAPSHOT_FETCH_SIZE;
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        private int offsetCommitIntervalMs = DEFAULT_OFFSET_COMMIT_INTERVAL_MS;
        private int snapshotMaxThreads = DEFAULT_SNAPSHOT_MAX_THREADS;
        private double snapshotMaxThreadsMultiplier = DEFAULT_SNAPSHOT_MAX_THREADS_MULTIPLIER;

        Builder(SnapshotConfig defaults) {
            this.offsetStoragePath = defaults.offsetStoragePath;
            this.schemaHistoryPath = defaults.schemaHistoryPath;
            this.snapshotMode = defaults.snapshotMode;
            this.batchInsertSize = defaults.batchInsertSize;
            this.snapshotFetchSize = defaults.snapshotFetchSize;
            this.maxQueueSize = defaults.maxQueueSize;
            this.pollIntervalMs = defaults.pollIntervalMs;
            this.offsetCommitIntervalMs = defaults.offsetCommitIntervalMs;
            this.snapshotMaxThreads = defaults.snapshotMaxThreads;
            this.snapshotMaxThreadsMultiplier = defaults.snapshotMaxThreadsMultiplier;
        }

        public Builder offsetStoragePath(String v) { this.offsetStoragePath = v; return this; }
        public Builder schemaHistoryPath(String v) { this.schemaHistoryPath = v; return this; }
        public Builder snapshotMode(String v) { this.snapshotMode = v; return this; }
        public Builder batchInsertSize(int v) { this.batchInsertSize = v; return this; }
        public Builder snapshotFetchSize(int v) { this.snapshotFetchSize = v; return this; }
        public Builder maxQueueSize(int v) { this.maxQueueSize = v; return this; }
        public Builder pollIntervalMs(int v) { this.pollIntervalMs = v; return this; }
        public Builder offsetCommitIntervalMs(int v) { this.offsetCommitIntervalMs = v; return this; }
        public Builder snapshotMaxThreads(int v) { this.snapshotMaxThreads = v; return this; }
        public Builder snapshotMaxThreadsMultiplier(double v) { this.snapshotMaxThreadsMultiplier = v; return this; }

        public SnapshotConfig build() {
            return new SnapshotConfig(
                    offsetStoragePath, schemaHistoryPath, snapshotMode,
                    batchInsertSize, snapshotFetchSize, maxQueueSize,
                    pollIntervalMs, offsetCommitIntervalMs,
                    snapshotMaxThreads, snapshotMaxThreadsMultiplier);
        }
    }
}
