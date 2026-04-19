package com.tool.dump.extractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlServerDumpExtractorTest {

    private SqlServerDumpExtractor extractor;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private ResultSetMetaData meta;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new SqlServerDumpExtractor();
        conn      = mock(Connection.class);
        ps        = mock(PreparedStatement.class);
        rs        = mock(ResultSet.class);
        meta      = mock(ResultSetMetaData.class);

        when(conn.prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
    }

    @Test
    void streamTable_setsCorrectFetchSize() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false); // empty table

        extractor.streamTable(conn, "dbo", "users", 500, batch -> {});

        verify(ps).setFetchSize(500);
    }

    @Test
    void streamTable_usesForwardOnlyCursor() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "users", 500, batch -> {});

        verify(conn).prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void streamTable_emptyTable_consumerNeverCalled() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "users", 10, batches::add);

        assertTrue(batches.isEmpty(), "No batches for empty table");
    }

    @Test
    void streamTable_threeRows_oneBatch() throws Exception {
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnName(2)).thenReturn("name");
        // 3 rows then done
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2, 3);
        when(rs.getObject(2)).thenReturn("a", "b", "c");

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "users", 10, batches::add);

        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).rows().size());
        assertEquals(0, batches.get(0).batchIndex());
    }

    @Test
    void streamTable_rowsExactlyOneChunk_oneBatch() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        // exactly chunkSize=3 rows
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2, 3);

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "t", 3, batches::add);

        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).rows().size());
    }

    @Test
    void streamTable_fiveRowsChunkThree_twoBatches() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, true, true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2, 3, 4, 5);

        List<RowBatch> batches = new ArrayList<>();
        extractor.streamTable(conn, "dbo", "t", 3, batches::add);

        assertEquals(2, batches.size());
        assertEquals(3, batches.get(0).rows().size());
        assertEquals(0, batches.get(0).batchIndex());
        assertEquals(2, batches.get(1).rows().size());
        assertEquals(1, batches.get(1).batchIndex());
    }

    @Test
    void streamTable_sqlContainsNOLOCK_byDefault() throws Exception {
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        extractor.streamTable(conn, "dbo", "orders", 10, b -> {});

        verify(conn).prepareStatement(
                argThat(sql -> sql.toUpperCase().contains("NOLOCK")),
                eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY));
    }

    // ── estimateRowCount ──────────────────────────────────────────────────────

    @Test
    void estimateRowCount_returnsStatisticsValue() throws Exception {
        Statement st = mock(Statement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.getLong(1)).thenReturn(42_000L);

        long count = extractor.estimateRowCount(conn, "dbo", "orders");

        assertEquals(42_000L, count);
    }
}
