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

/**
 * Adversarial tests for SqlServerExtractor — targets edge cases in SQL result
 * parsing that could produce incorrect TableSchema objects.
 */
class SqlServerExtractorAdversarialTest {

    private SqlServerExtractor extractor;
    private Connection conn;

    @BeforeEach
    void setUp() {
        extractor = new SqlServerExtractor();
        conn = mock(Connection.class);
    }

    // ── extractColumns: negative max_length (varchar(max) = -1) ────────────

    @Test
    void extractColumns_varcharMax_negativeMaxLength() throws Exception {
        // SQL Server varchar(max): max_length = -1
        // The code does: maxLen == 0 ? null : maxLen → -1 is NOT 0, so maxLength = -1
        // This is a bug: TypeMapper sees maxLength=-1 → NVARCHAR(MAX) → LONGTEXT (correct),
        // but for varchar(non-max), maxLength is in chars (not bytes), so -1 is unexpected.
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("big_text");
        when(colRs.getString("type_name")).thenReturn("varchar");
        when(colRs.getInt("max_length")).thenReturn(-1);  // varchar(max)
        when(colRs.getInt("precision")).thenReturn(0);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(true);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn(null);
        when(colRs.getString("comment")).thenReturn(null);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(colRs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        // We can't call extractColumns directly (private), so call extractTable with stubs
        stubEmptySubqueries(conn);
        TableSchema table = extractor.extractTable(conn, "dbo", "t");

        ColumnSchema col = table.getColumns().get(0);
        assertEquals(-1, col.getMaxLength(),
                "varchar(max) produces max_length=-1 — this should be handled by TypeMapper");
    }

    // ── extractColumns: precision/scale edge cases ───────────────────────────

    @Test
    void extractColumns_decimal_38_0_precisionScale() throws Exception {
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("amount");
        when(colRs.getString("type_name")).thenReturn("decimal");
        when(colRs.getInt("max_length")).thenReturn(17);
        when(colRs.getInt("precision")).thenReturn(38);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(false);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn(null);
        when(colRs.getString("comment")).thenReturn(null);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(colRs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        stubEmptySubqueries(conn);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        ColumnSchema col = table.getColumns().get(0);
        assertEquals(38, col.getPrecision());
        assertEquals(0, col.getScale());
    }

    // ── extractColumns: uses setSqlServerType consistently ───────────────────

    @Test
    void extractColumns_usesSetSqlServerType() throws Exception {
        // SqlServerExtractor calls col.setSqlServerType().
        // TypeMapper.mapType() reads col.getSqlServerType() — they must agree.
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("flag");
        when(colRs.getString("type_name")).thenReturn("bit");
        when(colRs.getInt("max_length")).thenReturn(1);
        when(colRs.getInt("precision")).thenReturn(0);
        when(colRs.getInt("scale")).thenReturn(0);
        when(colRs.getBoolean("is_nullable")).thenReturn(true);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn(null);
        when(colRs.getString("comment")).thenReturn(null);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(colRs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        stubEmptySubqueries(conn);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        ColumnSchema col = table.getColumns().get(0);
        assertEquals("bit", col.getSqlServerType(),
                "setSqlServerType should be populated from type_name column");
    }

    // ── listTables: empty string in filter bypasses filter ───────────────────

    @Test
    void listTables_emptyStringInSchemaFilter_bypassesFilter() throws Exception {
        // schemasFilter = List.of("") — !schemasFilter.isEmpty() is false,
        // so the AND clause is appended with 1 placeholder.
        // But the value "" matches nothing in the DB, so 0 rows returned.
        // This is NOT a security bug (parameterized), but worth documenting.
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        List<String[]> result = extractor.listTables(conn, List.of(""), null);
        assertTrue(result.isEmpty(), "Empty string in schema filter should return no tables");
        // Verify the SQL was built with a placeholder (filter was applied)
        verify(ps).setString(1, "");
    }

    // ── extractTable: subquery failure leaves partial table ──────────────────

    @Test
    void extractTable_columnsSucceedButPkQueryFails_throwsException() throws Exception {
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

        PreparedStatement colPs = mock(PreparedStatement.class);
        when(colPs.executeQuery()).thenReturn(colRs);

        // PK query throws — the partial TableSchema (with columns but no PK) is lost
        PreparedStatement pkPs = mock(PreparedStatement.class);
        when(pkPs.executeQuery()).thenThrow(new SQLException("PK query failed", "HY000", 50000));

        when(conn.prepareStatement(anyString())).thenReturn(colPs, pkPs);

        assertThrows(SQLException.class,
                () -> extractor.extractTable(conn, "dbo", "t"),
                "If PK query fails, exception propagates — partial table is lost");
    }

    // ── extractIndexes: COLUMNSTORE detection ─────────────────────────────────

    @Test
    void extractIndexes_columnstoreDetected() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(true, false);
        when(idxRs.getString("name")).thenReturn("IX_cs_data");
        when(idxRs.getBoolean("is_unique")).thenReturn(true);
        when(idxRs.getString("type_desc")).thenReturn("NONCLUSTERED COLUMNSTORE");
        when(idxRs.getString("filter_definition")).thenReturn(null);
        when(idxRs.getInt("is_fulltext")).thenReturn(0);

        ResultSet idxColRs = mock(ResultSet.class);
        when(idxColRs.next()).thenReturn(true, false);
        when(idxColRs.getString("name")).thenReturn("data");
        when(idxColRs.getBoolean("is_included_column")).thenReturn(false);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        PreparedStatement idxColPs = mock(PreparedStatement.class);
        when(idxColPs.executeQuery()).thenReturn(idxColRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        // columns, pk, check, uq, indexes(outer), indexes(inner col query), fk, partition
        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, idxPs, idxColPs, countPs, countPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals(1, table.getIndexes().size());
        IndexSchema idx = table.getIndexes().get(0);
        assertTrue(idx.isColumnstore(), "NONCLUSTERED COLUMNSTORE should be detected");
        assertFalse(idx.isClustered());
    }

    // ── extractIndexes: FULLTEXT detection ───────────────────────────────────

    @Test
    void extractIndexes_fulltextDetected() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(true, false);
        when(idxRs.getString("name")).thenReturn("FT_body");
        when(idxRs.getBoolean("is_unique")).thenReturn(false);
        when(idxRs.getString("type_desc")).thenReturn("NONCLUSTERED");
        when(idxRs.getString("filter_definition")).thenReturn(null);
        when(idxRs.getInt("is_fulltext")).thenReturn(1);  // is_fulltext = 1

        ResultSet idxColRs = mock(ResultSet.class);
        when(idxColRs.next()).thenReturn(true, false);
        when(idxColRs.getString("name")).thenReturn("body");
        when(idxColRs.getBoolean("is_included_column")).thenReturn(false);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        PreparedStatement idxColPs = mock(PreparedStatement.class);
        when(idxColPs.executeQuery()).thenReturn(idxColRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, idxPs, idxColPs, countPs, countPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals(1, table.getIndexes().size());
        assertTrue(table.getIndexes().get(0).isFulltext(), "is_fulltext=1 should be detected");
    }

    // ── extractIndexes: filtered index ───────────────────────────────────────

    @Test
    void extractIndexes_filteredIndex_filterDefinitionCaptured() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(true, false);
        when(idxRs.getString("name")).thenReturn("IX_active");
        when(idxRs.getBoolean("is_unique")).thenReturn(false);
        when(idxRs.getString("type_desc")).thenReturn("NONCLUSTERED");
        when(idxRs.getString("filter_definition")).thenReturn("[status] <> (0)");
        when(idxRs.getInt("is_fulltext")).thenReturn(0);

        ResultSet idxColRs = mock(ResultSet.class);
        when(idxColRs.next()).thenReturn(true, false);
        when(idxColRs.getString("name")).thenReturn("status");
        when(idxColRs.getBoolean("is_included_column")).thenReturn(false);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        PreparedStatement idxColPs = mock(PreparedStatement.class);
        when(idxColPs.executeQuery()).thenReturn(idxColRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, idxPs, idxColPs, countPs, countPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals("[status] <> (0)", table.getIndexes().get(0).getFilterDefinition(),
                "Filtered index WHERE clause should be captured");
    }

    // ── extractIndexes: INCLUDE columns ──────────────────────────────────────

    @Test
    void extractIndexes_includeColumnsSeparatedFromKeyColumns() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(true, false);
        when(idxRs.getString("name")).thenReturn("IX_covering");
        when(idxRs.getBoolean("is_unique")).thenReturn(false);
        when(idxRs.getString("type_desc")).thenReturn("NONCLUSTERED");
        when(idxRs.getString("filter_definition")).thenReturn(null);
        when(idxRs.getInt("is_fulltext")).thenReturn(0);

        // Key column first, then INCLUDE column (ORDER BY is_included_column, key_ordinal)
        ResultSet idxColRs = mock(ResultSet.class);
        when(idxColRs.next()).thenReturn(true, true, false);
        when(idxColRs.getString("name")).thenReturn("id", "name", "email");
        when(idxColRs.getBoolean("is_included_column")).thenReturn(false, true, true);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        PreparedStatement idxColPs = mock(PreparedStatement.class);
        when(idxColPs.executeQuery()).thenReturn(idxColRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, idxPs, idxColPs, countPs, countPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        IndexSchema idx = table.getIndexes().get(0);
        assertEquals(List.of("id"), idx.getColumns(), "Only key columns, not INCLUDE");
        assertEquals(List.of("name", "email"), idx.getIncludeColumns(), "INCLUDE columns separated");
    }

    // ── listDatabases: SQLException propagation ──────────────────────────────

    @Test
    void listDatabases_sqlException_propagates() throws Exception {
        when(conn.createStatement()).thenThrow(new SQLException("Connection broken", "08001", 0));
        assertThrows(Exception.class, () -> extractor.listDatabases(conn));
    }

    // ── listTables: SQLException on prepareStatement ─────────────────────────

    @Test
    void listTables_sqlException_propagates() throws Exception {
        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("Invalid SQL", "42000", 0));
        assertThrows(Exception.class, () -> extractor.listTables(conn, List.of("dbo"), null));
    }

    // ── countForeignKeys: rs.next() returns false ────────────────────────────

    @Test
    void extractTable_fkCountRsNoRows_returnsZero() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(false);  // no rows → 0
        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(true);
        when(partRs.getInt(1)).thenReturn(0);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, emptyPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals(0, table.getForeignKeyCount(),
                "FK count query with no rows should return 0, not throw NPE");
    }

    // ── isPartitioned: rs.next() returns false ───────────────────────────────

    @Test
    void extractTable_partitionRsNoRows_returnsFalse() throws Exception {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(true);
        when(fkRs.getInt(1)).thenReturn(0);
        ResultSet partRs = mock(ResultSet.class);
        when(partRs.next()).thenReturn(false);  // no rows → false

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        PreparedStatement partPs = mock(PreparedStatement.class);
        when(partPs.executeQuery()).thenReturn(partRs);

        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, emptyPs, fkPs, partPs);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertFalse(table.isPartitioned(),
                "Partition query with no rows should return false, not throw NPE");
    }

    // ── extractColumns: column with default value containing parens ──────────

    @Test
    void extractColumns_defaultValueWithParens_preserved() throws Exception {
        // SQL Server wraps defaults in parens: ((GETDATE())), ((0)), etc.
        // The extractor should preserve them as-is — SchemaConverter/TypeMapper handles stripping.
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("name")).thenReturn("created");
        when(colRs.getString("type_name")).thenReturn("datetime");
        when(colRs.getInt("max_length")).thenReturn(8);
        when(colRs.getInt("precision")).thenReturn(23);
        when(colRs.getInt("scale")).thenReturn(3);
        when(colRs.getBoolean("is_nullable")).thenReturn(false);
        when(colRs.getBoolean("is_identity")).thenReturn(false);
        when(colRs.getBoolean("is_computed")).thenReturn(false);
        when(colRs.getString("default_value")).thenReturn("((GETDATE()))");
        when(colRs.getString("comment")).thenReturn("row creation time");

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(colRs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        stubEmptySubqueries(conn);

        TableSchema table = extractor.extractTable(conn, "dbo", "t");
        assertEquals("((GETDATE()))", table.getColumns().get(0).getDefaultValue(),
                "Parens in default value should be preserved as-is from SQL Server");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubEmptySubqueries(Connection conn) throws SQLException {
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getInt(1)).thenReturn(0);

        PreparedStatement emptyPs = mock(PreparedStatement.class);
        when(emptyPs.executeQuery()).thenReturn(emptyRs);
        PreparedStatement countPs = mock(PreparedStatement.class);
        when(countPs.executeQuery()).thenReturn(countRs);

        // col, pk, check, uq, idx, fk, partition — columns already stubbed by caller
        // We stub pk onwards (6 more after columns)
        when(conn.prepareStatement(anyString()))
                .thenReturn(emptyPs, emptyPs, emptyPs, emptyPs, emptyPs, countPs, countPs);
    }
}
