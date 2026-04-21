package com.tool.snapshot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTableResultTest {

    @Test
    void successResult_isNotError() {
        SnapshotTableResult r = new SnapshotTableResult("db1", "dbo", "Users", 100, 500L, null);
        assertFalse(r.isError());
        assertEquals("db1", r.dbName());
        assertEquals("dbo.Users", r.schema() + "." + r.table());
    }

    @Test
    void errorResult_isError() {
        SnapshotTableResult r = new SnapshotTableResult("db1", "dbo", "Bad", 0, 0L, "table not found");
        assertTrue(r.isError());
        assertEquals("table not found", r.error());
    }
}
