package com.tool.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotConfigTest {

    @Test
    void defaults_matchSpec() {
        SnapshotConfig cfg = SnapshotConfig.defaults();
        assertEquals("snapshot-offsets", cfg.offsetStoragePath());
        assertEquals("snapshot-schema-history", cfg.schemaHistoryPath());
        assertEquals("initial_only", cfg.snapshotMode());
        assertEquals(5000, cfg.batchInsertSize());
        assertEquals(2000, cfg.snapshotFetchSize());
        assertEquals(8192, cfg.maxQueueSize());
        assertEquals(500, cfg.pollIntervalMs());
        assertEquals(10000, cfg.offsetCommitIntervalMs());
        assertEquals(1, cfg.snapshotMaxThreads());
        assertEquals(1.0, cfg.snapshotMaxThreadsMultiplier());
    }

    @Test
    void builder_overridesDefaults() {
        SnapshotConfig cfg = SnapshotConfig.builder()
                .batchInsertSize(1000)
                .snapshotMaxThreads(4)
                .build();
        assertEquals(1000, cfg.batchInsertSize());
        assertEquals(4, cfg.snapshotMaxThreads());
        assertEquals("initial_only", cfg.snapshotMode());
    }

    @Test
    void withBatchSize_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withBatchInsertSize(999);
        assertNotSame(original, modified);
        assertEquals(999, modified.batchInsertSize());
        assertEquals(5000, original.batchInsertSize());
    }

    @Test
    void withFetchSize_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withSnapshotFetchSize(5000);
        assertEquals(5000, modified.snapshotFetchSize());
        assertEquals(2000, original.snapshotFetchSize());
    }

    @Test
    void withSnapshotThreads_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withSnapshotMaxThreads(8);
        assertEquals(8, modified.snapshotMaxThreads());
        assertEquals(1, original.snapshotMaxThreads());
    }
}
