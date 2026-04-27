package com.tool.dump.extractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for SqlServerDumpExtractor — targets SQL injection,
 * edge-case JDBC behavior, and error propagation.
 */
class SqlServerDumpExtractorAdversarialTest {

    private SqlServerDumpExtractor extractor;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private ResultSetMetaData meta;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new SqlServerDumpExtractor();
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        meta = mock(ResultSetMetaData.class);

        when(conn.prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
    }

    // ── SQL injection via schema/table names ─────────────────────────────────

    @Test
    void streamTable_schemaWithClosingBracket_escaped() throws Exception {
        // Schema containing `]` is now escaped as `]]`
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "my]schema", "t", 10, batch -> {});

        // ] is escaped as ]] inside bracket quoting
        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[my]]schema].[t]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void streamTable_tableWithClosingBracket_escaped() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "my]table", 10, batch -> {});

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[dbo].[my]]table]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── chunkSize = 0: implementation-specific behavior ──────────────────────

    @Test
    void streamTable_chunkSizeZero_setsFetchSizeZero() throws Exception {
        // JDBC spec: setFetchSize(0) means "implementation-defined"
        // Some drivers load all rows into memory — defeats server-side cursor
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "t", 0, batch -> {});

        verify(ps).setFetchSize(0);
    }

    @Test
    void streamTable_negativeChunkSize_setsFetchSizeNegative() throws Exception {
        // Some JDBC drivers throw on negative fetchSize
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "t", -1, batch -> {});

        verify(ps).setFetchSize(-1);
    }

    // ── Consumer throws RuntimeException ─────────────────────────────────────

    @Test
    void streamTable_consumerThrows_propagatesException() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1);

        RuntimeException consumerError = new RuntimeException("consumer exploded");
        assertThrows(RuntimeException.class,
                () -> extractor.streamTable(conn, "dbo", "t", 10,
                        batch -> { throw consumerError; }),
                "Exception from consumer must propagate — partial data may be lost");

        // Verify PreparedStatement and ResultSet are still closed (try-with-resources)
        verify(ps).close();
    }

    @Test
    void streamTable_consumerThrowsMidBatch_partialDataLost() throws Exception {
        // 5 rows, chunkSize=5. Consumer throws after first batch.
        // Second batch of rows is never delivered.
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, true, true, true, true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2, 3, 4, 5, 6, 7);

        List<RowBatch> received = new ArrayList<>();
        assertThrows(RuntimeException.class,
                () -> extractor.streamTable(conn, "dbo", "t", 5,
                        batch -> {
                            received.add(batch);
                            if (batch.batchIndex() == 1) {
                                throw new RuntimeException("fail on batch 2");
                            }
                        }));

        assertEquals(2, received.size(), "Batch 0 received, batch 1 received, then exception");
        assertEquals(5, received.get(0).rows().size());
        assertEquals(2, received.get(1).rows().size());
        // Rows 6 and 7 (batch index 2) are never delivered
    }

    // ── getColumnNames with useNolock=false ──────────────────────────────────

    @Test
    void getColumnNames_nolockFalse_noNOLOCKHint() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");

        extractor.getColumnNames(conn, "dbo", "t", false);

        verify(conn).prepareStatement(
                argThat(sql -> !sql.toUpperCase().contains("NOLOCK")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void getColumnNames_nolockTrue_hasNOLOCKHint() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");

        extractor.getColumnNames(conn, "dbo", "t", true);

        verify(conn).prepareStatement(
                argThat(sql -> sql.toUpperCase().contains("NOLOCK")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── getColumnNames: zero columns ─────────────────────────────────────────

    @Test
    void getColumnNames_zeroColumns_returnsEmptyList() throws Exception {
        when(meta.getColumnCount()).thenReturn(0);

        List<String> cols = extractor.getColumnNames(conn, "dbo", "empty_table");

        assertTrue(cols.isEmpty(),
                "Table with zero columns should return empty column list");
    }

    // ── estimateRowCount: SUM returns NULL (no matching rows) ────────────────

    @Test
    void estimateRowCount_noMatchingRows_returnsZero() throws Exception {
        // SUM(p.rows) returns NULL when no rows match → rs.next() = false
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(cntPs);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(false);

        long count = extractor.estimateRowCount(conn, "dbo", "nonexistent");

        assertEquals(0L, count,
                "Non-existent table should return 0, not throw exception");
    }

    @Test
    void estimateRowCount_nullSum_returnsZero() throws Exception {
        // SUM returns NULL → getLong(1) returns 0 (JDBC spec for NULL → 0 for primitives)
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(cntPs);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.wasNull()).thenReturn(true);
        when(cntRs.getLong(1)).thenReturn(0L);

        long count = extractor.estimateRowCount(conn, "dbo", "t");

        assertEquals(0L, count);
    }

    // ── estimateRowCount: schema/table names are parameterized ───────────────

    @Test
    void estimateRowCount_usesParameterizedQuery() throws Exception {
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(cntPs);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.getLong(1)).thenReturn(100L);

        extractor.estimateRowCount(conn, "dbo", "orders");

        // Verify schema and table are set via setString (parameterized), not concatenated
        verify(cntPs).setString(1, "dbo");
        verify(cntPs).setString(2, "orders");
    }

    // ── streamTable: large column count ──────────────────────────────────────

    @Test
    void streamTable_100Columns_correctObjectArraySize() throws Exception {
        when(meta.getColumnCount()).thenReturn(100);
        for (int i = 1; i <= 100; i++) {
            when(meta.getColumnName(i)).thenReturn("col" + i);
        }
        when(rs.next()).thenReturn(true, false);
        for (int i = 1; i <= 100; i++) {
            when(rs.getObject(i)).thenReturn(i);
        }

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "wide_table", 10, batches::add);

        assertEquals(1, batches.size());
        assertEquals(100, batches.get(0).rows().get(0).length,
                "Row array length must match column count");
    }

    // ── streamTable: rs.getObject returns null for a column ──────────────────

    @Test
    void streamTable_nullColumnValue_inRow() throws Exception {
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnName(2)).thenReturn("name");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(42);
        when(rs.getObject(2)).thenReturn(null); // NULL from database

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "t", 10, batches::add);

        Object[] row = batches.get(0).rows().get(0);
        assertEquals(42, row[0]);
        assertNull(row[1], "NULL from getObject should be null in row array");
    }

    // ── streamTable: PreparedStatement/ResultSet closed on exception ─────────

    @Test
    void streamTable_executeQueryThrows_resourcesStillClosed() throws Exception {
        when(ps.executeQuery()).thenThrow(new SQLException("query failed", "HY000", 50000));

        assertThrows(SQLException.class,
                () -> extractor.streamTable(conn, "dbo", "t", 10, batch -> {}));

        // try-with-resources ensures PreparedStatement is closed
        verify(ps).close();
    }

    // ── getColumnNames: SQL injection via schema/table (same bug) ───────────

    @Test
    void getColumnNames_schemaWithBracket_escaped() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");

        extractor.getColumnNames(conn, "my]schema", "t");

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[my]]schema].[t]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void getColumnNames_tableWithBracket_escaped() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");

        extractor.getColumnNames(conn, "dbo", "my]table");

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[dbo].[my]]table]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── streamTable: rs.getObject throws for a column ────────────────────────

    @Test
    void streamTable_getObjectThrowsMidRow_propagatesException() throws Exception {
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnName(2)).thenReturn("data");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1);
        when(rs.getObject(2)).thenThrow(new SQLException("LOB read failed", "HY000", 50000));

        assertThrows(SQLException.class,
                () -> extractor.streamTable(conn, "dbo", "t", 10, batch -> {}),
                "Exception from getObject for a column should propagate");
    }

    // ── streamTable: useNolock=false via 5-arg method ───────────────────────

    @Test
    void streamTable_nolockFalse_noNOLOCKHint() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "t", 10, false, batch -> {});

        verify(conn).prepareStatement(
                argThat(sql -> !sql.toUpperCase().contains("NOLOCK")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── streamTable: schema/table with square bracket edge cases ─────────────

    @Test
    void streamTable_schemaWithDot_notSQLInjectionButMayQueryWrongObject() throws Exception {
        // "dbo.sub" as schema → [dbo.sub].[table]
        // SQL Server treats this as schema "dbo.sub" — doesn't exist
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo.sub", "t", 10, batch -> {});

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[dbo.sub].[t]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── streamTable: empty schema/table name ────────────────────────────────

    @Test
    void streamTable_emptySchema_producesInvalidSQL() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "", "t", 10, batch -> {});

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[].[t]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void streamTable_emptyTable_producesInvalidSQL() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "", 10, batch -> {});

        verify(conn).prepareStatement(
                argThat(sql -> sql.contains("[dbo].[  ]")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── estimateRowCount: very large count (overflow risk) ───────────────────

    @Test
    void estimateRowCount_veryLargeValue_noOverflow() throws Exception {
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(cntPs);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.getLong(1)).thenReturn(Long.MAX_VALUE);

        long count = extractor.estimateRowCount(conn, "dbo", "huge_table");

        assertEquals(Long.MAX_VALUE, count,
                "Very large row count should be returned as-is without overflow");
    }

    // ── estimateRowCount: negative value from sys.partitions ─────────────────

    @Test
    void estimateRowCount_negativeValue_clampedToZero() throws Exception {
        // sys.partitions.rows can be negative after large deletes/rollbacks
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(cntPs);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.getLong(1)).thenReturn(-42L);

        long count = extractor.estimateRowCount(conn, "dbo", "corrupted");

        assertEquals(0L, count,
                "Negative row count clamped to 0");
    }

    // ── streamTable: Integer.MAX_VALUE chunkSize ────────────────────────────

    @Test
    void streamTable_maxIntChunkSize_setsFetchSizeMaxInt() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "t", Integer.MAX_VALUE, batch -> {});

        verify(ps).setFetchSize(Integer.MAX_VALUE);
    }
}
