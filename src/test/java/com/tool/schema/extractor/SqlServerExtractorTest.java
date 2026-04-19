package com.tool.schema.extractor;

import com.tool.common.model.ColumnSchema;
import com.tool.common.model.IndexSchema;
import com.tool.common.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SqlServerExtractorTest {

    private SqlServerExtractor extractor;
    private Connection conn;

    @BeforeEach
    void setUp() {
        extractor = new SqlServerExtractor();
        conn = mock(Connection.class);
    }

    // ── listDatabases ───────────────────────────────────────────────────────

    @Test
    void listDatabases_returnsDatabaseNames() throws Exception {
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("AppDB", "ReportDB");

        List<String> dbs = extractor.listDatabases(conn);

        assertEquals(List.of("AppDB", "ReportDB"), dbs);
    }

    @Test
    void listDatabases_empty_returnsEmptyList() throws Exception {
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertTrue(extractor.listDatabases(conn).isEmpty());
    }

    // ── listTables ──────────────────────────────────────────────────────────

    @Test
    void listTables_noFilter_returnsAll() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("schema_name")).thenReturn("dbo");
        when(rs.getString("table_name")).thenReturn("users");

        List<String[]> tables = extractor.listTables(conn, null, null);

        assertEquals(1, tables.size());
        assertArrayEquals(new String[]{"dbo", "users"}, tables.get(0));
    }

    @Test
    void listTables_withSchemaFilter_setsParameter() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        extractor.listTables(conn, List.of("dbo"), null);

        verify(ps, atLeastOnce()).setString(eq(1), eq("dbo"));
    }

    // ── extractTable via extractColumns ─────────────────────────────────────

    /**
     * extractTable orchestrates multiple queries. We stub all PreparedStatement
     * calls and verify the returned TableSchema is correctly assembled.
     */
    @Test
    void extractTable_basicColumns_parsedCorrectly() throws Exception {
        // Columns RS: id INT IDENTITY NOT NULL
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("id");
        when(colRs.getString("type_name")).thenReturn("int");
        when(colRs.getInt("max_length")).thenReturn(4);
        when(colRs.getInt("precision")).thenReturn(10);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(false);
        when(colRs.getBoolean("is_identity")).thenReturn(true);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn(null);
        when(colRs.getString("comment")).thenReturn(null);

        // PK RS: id
        ResultSet pkRs = mock(ResultSet.class);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("name")).thenReturn("id");

        // Check, UniqueConstraints, Index RS: empty
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);

        // FK count RS
        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(true);
        when(fkRs.getInt(1)).thenReturn(0);

        // Partition RS
        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(true);
        when(partRs.getInt(1)).thenReturn(0);

        // Return PreparedStatements in call order:
        // 1=columns, 2=pk, 3=checks, 4=uniqueConstraints, 5=indexes, 6=fk, 7=partition
        PreparedStatement colPs = mock(PreparedStatement.class);
        when(colPs.executeQuery()).thenReturn(colRs);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        PreparedStatement checkPs = mock(PreparedStatement.class);
        when(checkPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement uqPs = mock(PreparedStatement.class);
        when(uqPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(colPs, pkPs, checkPs, uqPs, idxPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "users");

        assertEquals("dbo", table.getSchemaName());
        assertEquals("users", table.getTableName());
        assertEquals(1, table.getColumns().size());
        ColumnSchema col = table.getColumns().get(0);
        assertEquals("id", col.getName());
        assertEquals("int", col.getSqlServerType());
        assertFalse(col.isNullable());
        assertTrue(col.isIdentity());
        assertEquals(List.of("id"), table.getPrimaryKeyColumns());
        assertEquals(0, table.getForeignKeyCount());
        assertFalse(table.isPartitioned());
    }

    @Test
    void extractTable_computedColumn_flaggedCorrectly() throws Exception {
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("full_name");
        when(colRs.getString("type_name")).thenReturn("varchar");
        when(colRs.getInt("max_length")).thenReturn(200);
        when(colRs.getInt("precision")).thenReturn(0);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(true);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(true);
        when(colRs.getString("default_value")).thenReturn(null);
        when(colRs.getString("comment")).thenReturn(null);

        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);

        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        PreparedStatement colPs = mock(PreparedStatement.class);
        when(colPs.executeQuery()).thenReturn(colRs);
        PreparedStatement other = mock(PreparedStatement.class);
        when(other.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(countRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(countRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(colPs, other, other, other, other, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertTrue(table.getColumns().get(0).isComputed());
    }

    @Test
    void extractTable_withForeignKeys_countsCorrectly() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);

        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(true);
        when(fkRs.getInt(1)).thenReturn(3);

        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(true);
        when(partRs.getInt(1)).thenReturn(0);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        // col, pk, check, uq, idx return empty; fk returns 3; partition returns 0
        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, emptyPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals(3, table.getForeignKeyCount());
    }

    @Test
    void extractTable_partitioned_flaggedCorrectly() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);

        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(true);
        when(fkRs.getInt(1)).thenReturn(0);

        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(true);
        when(partRs.getInt(1)).thenReturn(2); // > 1 → partitioned

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, emptyPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertTrue(table.isPartitioned());
    }

    @Test
    void extractTable_withIndex_parsedCorrectly() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);

        // Index result set: one NONCLUSTERED index
        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(true, false);
        when(idxRs.getString("name")).thenReturn("IX_code");
        when(idxRs.getBoolean("is_unique")).thenReturn(false);
        when(idxRs.getString("type_desc")).thenReturn("NONCLUSTERED");
        when(idxRs.getString("filter_definition")).thenReturn(null);
        when(idxRs.getInt("is_fulltext")).thenReturn(0);

        // Index columns result set: one key column
        ResultSet idxColRs = mock(ResultSet.class);
        when(idxColRs.next()).thenReturn(true, false);
        when(idxColRs.getString("name")).thenReturn("code");
        when(idxColRs.getBoolean("is_included_column")).thenReturn(false);

        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(true);
        when(fkRs.getInt(1)).thenReturn(0);
        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(true);
        when(partRs.getInt(1)).thenReturn(0);

        // col, pk, check, uq: empty; idx itself; idx col; fk; partition
        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        PreparedStatement idxColPs = mock(PreparedStatement.class);
        when(idxColPs.executeQuery()).thenReturn(idxColRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        // Order: columns, pk, check, uq, indexes(outer), indexes(inner col query), fk, partition
        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, idxPs, idxColPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals(1, table.getIndexes().size());
        IndexSchema idx = table.getIndexes().get(0);
        assertEquals("IX_code", idx.getName());
        assertFalse(idx.isUnique());
        assertFalse(idx.isClustered());
        assertFalse(idx.isColumnstore());
        assertEquals(List.of("code"), idx.getColumns());
        assertTrue(idx.getIncludeColumns().isEmpty());
    }

    @Test
    void extractTable_columnWithDefaultAndComment_parsed() throws Exception {
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("status");
        when(colRs.getString("type_name")).thenReturn("int");
        when(colRs.getInt("max_length")).thenReturn(4);
        when(colRs.getInt("precision")).thenReturn(10);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(false);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn("((0))");
        when(colRs.getString("comment")).thenReturn("0=inactive 1=active");

        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        PreparedStatement colPs = mock(PreparedStatement.class);
        when(colPs.executeQuery()).thenReturn(colRs);
        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(colPs, emptyPs, emptyPs, emptyPs, emptyPs, countPs, countPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        ColumnSchema col = table.getColumns().get(0);
        assertEquals("((0))", col.getDefaultValue());
        assertEquals("0=inactive 1=active", col.getComment());
    }

    @Test
    void listTables_withTableFilter_setsParameter() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        extractor.listTables(conn, List.of("dbo"), List.of("users"));

        // Both schema and table filter set: idx 1=dbo, idx 2=users
        verify(ps).setString(1, "dbo");
        verify(ps).setString(2, "users");
    }
}
