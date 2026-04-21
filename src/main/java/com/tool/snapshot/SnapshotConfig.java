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

    public static SnapshotConfig defaults() {
        return new SnapshotConfig(
                "snapshot-offsets",
                "snapshot-schema-history",
                "initial_only",
                5000,
                2000,
                8192,
                500,
                10000,
                1,
                1.0
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

    public SnapshotConfig withSnapshotMaxThreads(int snapshotMaxThreads) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public String buildTableIncludeList(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return "dbo\\..*";
        }
        return tables.stream()
                .map(t -> "dbo\\." + t)
                .collect(Collectors.joining(","));
    }

    public static class Builder {
        private String offsetStoragePath = "snapshot-offsets";
        private String schemaHistoryPath = "snapshot-schema-history";
        private String snapshotMode = "initial_only";
        private int batchInsertSize = 5000;
        private int snapshotFetchSize = 2000;
        private int maxQueueSize = 8192;
        private int pollIntervalMs = 500;
        private int offsetCommitIntervalMs = 10000;
        private int snapshotMaxThreads = 1;
        private double snapshotMaxThreadsMultiplier = 1.0;

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
