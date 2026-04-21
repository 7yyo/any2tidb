package com.tool.snapshot.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiDBBatchWriterTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;
    private TiDBBatchWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        writer = new TiDBBatchWriter(dataSource, new SinkRecordConverter(), 3);
    }

    private Map<String, Object> makeRow(int id, String name) {
        return Map.of("id", id, "name", name);
    }

    @Test
    void accumulate_belowThreshold_noFlush() {
        writer.accumulate("testdb", "users", makeRow(1, "Alice"));
        verifyNoInteractions(ps);
    }

    @Test
    void accumulate_atThreshold_flushes() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "users", makeRow(3, "C"));
        verify(ps, times(3)).addBatch();
        verify(ps).executeBatch();
    }

    @Test
    void flushAll_flushesRemaining() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.flushAll();
        verify(ps).addBatch();
        verify(ps).executeBatch();
    }

    @Test
    void totalRows_tracksCorrectly() {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "orders", makeRow(3, "C"));
        assertEquals(3, writer.getTotalRows());
    }

    @Test
    void tableRows_tracksPerTable() {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "orders", makeRow(3, "C"));
        Map<String, Long> tableRows = writer.getTableRows();
        assertEquals(2L, tableRows.get("users"));
        assertEquals(1L, tableRows.get("orders"));
    }

    @Test
    void flushAll_resetsBuffers() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.flushAll();
        assertEquals(0, writer.getTotalRows());
        assertTrue(writer.getTableRows().isEmpty());
    }

    @Test
    void accumulate_afterFlush_reusesConnection() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "users", makeRow(3, "C"));
        writer.accumulate("testdb", "users", makeRow(4, "D"));
        writer.accumulate("testdb", "users", makeRow(5, "E"));
        writer.accumulate("testdb", "users", makeRow(6, "F"));
        verify(ps, times(6)).addBatch();
        verify(ps, times(2)).executeBatch();
    }
}
