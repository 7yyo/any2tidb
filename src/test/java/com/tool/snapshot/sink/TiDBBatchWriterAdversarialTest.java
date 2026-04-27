package com.tool.snapshot.sink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for TiDBBatchWriter — targets SQL injection via
 * table/field names, batch flush edge cases, and concurrent access.
 */
class TiDBBatchWriterAdversarialTest {

    @TempDir
    Path tmp;

    // ── SQL injection via table name ─────────────────────────────────────────

    @Test
    void accumulate_tableNameWithQuote_sqlInjection() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        Map<String, Object> row = Map.of("id", 1);
        // Table name contains backtick — breaks SQL
        writer.accumulate("mydb", "my`; DROP TABLE users--", row);

        // Trigger flush by reaching batch size
        for (int i = 1; i < 100; i++) {
            writer.accumulate("mydb", "my`; DROP TABLE users--", row);
        }
        writer.flushAll();

        // Verify the SQL was constructed with escaped backticks
        verify(conn).prepareStatement(argThat(sql ->
                sql.contains("my``; DROP TABLE users--")));
    }

    // ── SQL injection via field name ─────────────────────────────────────────

    @Test
    void accumulate_fieldNameWithQuote_sqlInjection() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        // Field name contains backtick
        Map<String, Object> row = Map.of("id`; DROP TABLE x--", 1);
        writer.accumulate("mydb", "safe_table", row);

        for (int i = 1; i < 100; i++) {
            writer.accumulate("mydb", "safe_table", row);
        }
        writer.flushAll();

        verify(conn).prepareStatement(argThat(sql ->
                sql.contains("id``; DROP TABLE x--")));
    }

    // ── Empty after map triggers flush with 0 fields ───────────────────────────

    @Test
    void accumulate_emptyRow_mapsToEmptyFields() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        Map<String, Object> emptyRow = new LinkedHashMap<>();
        writer.accumulate("mydb", "t", emptyRow);

        for (int i = 1; i < 100; i++) {
            writer.accumulate("mydb", "t", emptyRow);
        }
        writer.flushAll();

        // SQL: INSERT INTO `mydb`.`t` () VALUES ()
        // Empty cols and empty placeholders — invalid SQL, but that's the behavior
        verify(conn).prepareStatement(contains("INSERT INTO"));
    }

    // ── flushAll on empty buffer ─────────────────────────────────────────────

    @Test
    void flushAll_emptyBuffer_noConnectionUsed() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        writer.flushAll(); // no exception, no rows

        verify(ds, never()).getConnection();
        assertEquals(0L, writer.getTotalRows());
    }

    // ── Table name with backtick in dbName ─────────────────────────────────────

    @Test
    void accumulate_dbNameWithQuote_sqlInjection() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        Map<String, Object> row = Map.of("id", 1);
        writer.accumulate("my`; DROP DATABASE mydb--", "t", row);

        for (int i = 1; i < 100; i++) {
            writer.accumulate("my`; DROP DATABASE mydb--", "t", row);
        }
        writer.flushAll();

        verify(conn).prepareStatement(argThat(sql ->
                sql.contains("my``; DROP DATABASE mydb--")));
    }

    // ── Row with null value → setNull ───────────────────────────────────────────

    @Test
    void accumulate_rowWithNullValue_boundCorrectly() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", null);
        writer.accumulate("mydb", "t", row);

        for (int i = 1; i < 100; i++) {
            writer.accumulate("mydb", "t", row);
        }
        writer.flushAll();

        // name field should be setNull
        verify(ps, atLeastOnce()).setNull(anyInt(), eq(Types.VARCHAR));
    }

    // ── getTableRows after flushAll ─────────────────────────────────────────────

    @Test
    void getTableRows_afterFlushAll_returnsZero() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        writer.accumulate("db1", "t1", Map.of("id", 1));
        writer.accumulate("db2", "t2", Map.of("id", 2));
        writer.flushAll();

        // flushAll clears tableRowCounts
        assertEquals(0, writer.getTableRows().size());
    }

    // ── getTableRows before flushAll ────────────────────────────────────────────

    @Test
    void getTableRows_beforeFlushAll_returnsCounts() throws Exception {
        DataSource ds = mock(DataSource.class);

        SinkRecordConverter converter = new SinkRecordConverter();
        TiDBBatchWriter writer = new TiDBBatchWriter(ds, converter, 100);

        writer.accumulate("db1", "t1", Map.of("id", 1));
        writer.accumulate("db1", "t1", Map.of("id", 2));
        writer.accumulate("db2", "t2", Map.of("id", 3));

        Map<String, Long> rows = writer.getTableRows();
        assertEquals(2L, rows.get("t1"));
        assertEquals(1L, rows.get("t2"));
        assertEquals(3L, writer.getTotalRows());
    }
}
