package com.tool.snapshot.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDbResultTest {

    @Test
    void successResult_isNotError() {
        SnapshotDbResult r = new SnapshotDbResult("db1", "00000025:00000338:0003",
                "00000025:00000338:0002", 3, 500L, Instant.now(), null);
        assertFalse(r.isError());
        assertEquals(3, r.tables());
        assertEquals(500L, r.rows());
    }

    @Test
    void errorResult_isError() {
        SnapshotDbResult r = new SnapshotDbResult("db1", null, null, 0, 0L, Instant.now(), "CDC not enabled");
        assertTrue(r.isError());
        assertEquals("CDC not enabled", r.error());
    }

    @Test
    void shouldBlockPipeline_trueWhenAnyError() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", null, null, 0, 0L, Instant.now(), "fail"))));
    }

    @Test
    void shouldBlockPipeline_falseWhenNoError() {
        assertFalse(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 1, 10L, Instant.now(), null))));
    }

    @Test
    void shouldBlockPipeline_trueWhenMixed() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 1, 10L, Instant.now(), null),
                new SnapshotDbResult("db2", null, null, 0, 0L, Instant.now(), "fail"))));
    }
}
