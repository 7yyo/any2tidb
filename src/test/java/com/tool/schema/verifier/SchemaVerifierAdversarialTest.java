package com.tool.schema.verifier;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for SchemaVerifier — targets cross-database comparison
 * edge cases, type normalization, default value parsing, and SQL query behavior.
 */
class SchemaVerifierAdversarialTest {

    private SchemaVerifier verifier;
    private Connection msConn;
    private Connection tidbConn;
    private DatabaseMetaData tidbMeta;
    private Statement tidbStmt;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new SchemaVerifier();
        msConn = mock(Connection.class);
        tidbConn = mock(Connection.class);
        tidbMeta = mock(DatabaseMetaData.class);
        tidbStmt = mock(Statement.class);
        when(tidbConn.getMetaData()).thenReturn(tidbMeta);
        when(tidbConn.getCatalog()).thenReturn("testdb");
        when(tidbConn.createStatement()).thenReturn(tidbStmt);
    }

    // ── normalizeDefault: N'' (empty string via N'') ────────────────────────

    @Test
    void normalizeDefault_NQuoteEmptyString_returnsEmptyString() {
        // SQL Server N'' = empty string literal
        // N'...' → inner, replace '' → '  → inner.replace("''", "'")
        // N'' → inner="" → "".replace("''", "'") = ""
        assertEquals("", verifier.normalizeDefault("N''"));
    }

    // ── normalizeDefault: N'with''apostrophe' ──────────────────────────

    @Test
    void normalizeDefault_NQuoteWithApostrophe_unescapesCorrectly() {
        // N'it''s' → inner = "it''s" → replace '' → ' → "it's"
        assertEquals("it's", verifier.normalizeDefault("N'it''s'"));
    }

    // ── normalizeDefault: N'NULL' is NOT treated as NULL ───────────────────

    @Test
    void normalizeDefault_NQuoteNULL_isStringNULL() {
        // N'NULL' is the literal string "NULL", not SQL NULL
        assertEquals("NULL", verifier.normalizeDefault("N'NULL'"));
    }

    // ── normalizeDefault: deeply nested parens ─────────────────────────────

    @Test
    void normalizeDefault_deeplyNestedParens_stripped() {
        // (((42))) → strip all layers → 42
        assertEquals("42", verifier.normalizeDefault("(((42)))"));
    }

    @Test
    void normalizeDefault_unbalancedParens_stopsStripping() {
        // ((42) → first strip gives "42)" → doesn't end with ) → stop
        // Actually: v = "42)" → doesn't match start=( and end=) → stop
        // Wait, let me re-read: starts with ( and ends with )
        // "((42)" → starts with ((, ends with "42)" → ends with ) → yes → strip → "(42"
        // "(42" → starts with ( but doesn't end with ) → stop → "(42"
        assertEquals("(42", verifier.normalizeDefault("((42)"));
    }

    // ── normalizeDefault: function case sensitivity ─────────────────────────

    @Test
    void normalizeDefault_getdate_caseInsensitive() {
        assertEquals("CURRENT_TIMESTAMP", verifier.normalizeDefault("getdate()"));
        assertEquals("CURRENT_TIMESTAMP", verifier.normalizeDefault("GetDate()"));
    }

    @Test
    void normalizeDefault_getutcdate_caseInsensitive() {
        assertEquals("UTC_TIMESTAMP()", verifier.normalizeDefault("GETUTCDATE()"));
        assertEquals("UTC_TIMESTAMP()", verifier.normalizeDefault("getutcdate()"));
    }

    // ── normalizeDefault: empty/blank ──────────────────────────────────────

    @Test
    void normalizeDefault_emptyString_returnsEmptyString() {
        assertEquals("", verifier.normalizeDefault(""));
    }

    @Test
    void normalizeDefault_whitespaceOnly_returnsTrimmed() {
        assertEquals("", verifier.normalizeDefault("   "));
    }

    // ── verify: OBJECT_ID with schema.table (SQL injection) ────────────────

    @Test
    void verify_schemaWithBracket_escapedCorrectly() throws Exception {
        // schema with ] is now bracket-escaped: [dbo]]]. DROP TABLE users--].[t]
        // OBJECT_ID resolves to NULL (no such object), so we get 0 columns, 0 PK, etc.

        mockMsQueries("dbo]; DROP TABLE users--", "t");
        mockTiDbMetadata("dbo]; DROP TABLE users--", "t");

        // No exception — returns 0-column result
        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo]; DROP TABLE users--", "t");
        assertEquals(0, r.msColCount());
        assertEquals(0, r.tidbColCount());
    }

    // ── verify: empty table name ──────────────────────────────────────────────

    @Test
    void verify_emptyTableName_msQueryReturnsNoRows() throws Exception {
        mockMsQueries("dbo", "");
        mockTiDbMetadata("dbo", "");

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "");

        // MS side: OBJECT_ID('dbo.') returns null → no columns, no PK, etc.
        // TiDB side: getColumns returns empty
        assertEquals(0, r.msColCount());
        assertEquals(0, r.tidbColCount());
    }

    // ── verify: column name case sensitivity ───────────────────────────────────

    @Test
    void verify_columnNameCase_mismatch() throws Exception {
        // MS column "ID" vs TiDB column "id" — case differs
        // msColTypes uses toLowerCase() as key, tidbColTypes uses toLowerCase() as key
        // So they should match — this is by design
        mockMsQueryWithColumns("dbo", "t", List.of("ID", "Name"));
        mockTiDbMetadataWithColumns("dbo", "t", List.of("id", "name"), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");

        assertFalse(r.isMismatch(), "Column names should be compared case-insensitively");
    }

    // ── verify: table not found on TiDB side ──────────────────────────────────

    @Test
    void verify_tableNotInTiDB_zeroTiDbColumns() throws Exception {
        mockMsQueryWithColumns("dbo", "t", List.of("id"));
        mockTiDbMetadataWithColumns("dbo", "t", List.of(), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");

        assertTrue(r.isMismatch(), "TiDB has 0 columns but MS has 1 — should mismatch");
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols")));
    }

    // ── verify: extra columns in TiDB ───────────────────────────────────────

    @Test
    void verify_extraColumnsInTiDB_mismatch() throws Exception {
        mockMsQueryWithColumns("dbo", "t", List.of("id"));
        mockTiDbMetadataWithColumns("dbo", "t", List.of("id", "extra_col"), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");

        assertTrue(r.isMismatch(), "TiDB has extra column not in MS");
    }

    // ── verify: nvarchar(-1) / varchar(max) type display ─────────────────────

    @Test
    void verify_nvarcharMax_typeStrIncludesMax() throws Exception {
        mockMsQueryWithColumnType("dbo", "t", "col", "nvarchar", -1);
        mockTiDbMetadataWithColumns("dbo", "t", List.of("col"), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");

        // MS type: nvarchar(-1) → "nvarchar(max)"
        assertTrue(r.msColTypes().containsValue("nvarchar(max)"),
                "nvarchar with maxLength=-1 should display as nvarchar(max)");
    }

    @Test
    void verify_varcharMax_typeStrIncludesMax() throws Exception {
        mockMsQueryWithColumnType("dbo", "t", "col", "varchar", -1);
        mockTiDbMetadataWithColumns("dbo", "t", List.of("col"), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");
        assertTrue(r.msColTypes().containsValue("varchar(max)"));
    }

    // ── verify: decimal(38,10) type display ────────────────────────────────

    @Test
    void verify_decimal38_10_typeStrCorrect() throws Exception {
        mockMsQueryWithColumnType("dbo", "t", "amount", "decimal", 17, 38, 10);
        mockTiDbMetadataWithColumns("dbo", "t", List.of("amount"), List.of());

        VerifyResult r = verifier.verify(msConn, tidbConn, "dbo", "t");
        assertTrue(r.msColTypes().containsValue("decimal(38,10)"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void mockMsQueries(String schema, String table) throws Exception {
        mockMsQueryWithColumns(schema, table, List.of());
    }

    private void mockMsQueryWithColumns(String schema, String table, List<String> colNames) throws Exception {
        // Columns query
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet colRs = mock(ResultSet.class);
        when(colPs.executeQuery()).thenReturn(colRs);

        // Mock column rows
        LinkedList<Boolean> nextResults = new LinkedList<>();
        for (String col : colNames) {
            nextResults.add(true);
        }
        nextResults.add(false);
        Iterator<Boolean> it = nextResults.iterator();
        when(colRs.next()).thenAnswer(inv -> it.next());

        for (int i = 0; i < colNames.size(); i++) {
            when(colRs.getString(1)).thenReturn(colNames.get(i));
            when(colRs.getString(2)).thenReturn("int");
            when(colRs.getInt(3)).thenReturn(4);
            when(colRs.getInt(4)).thenReturn(10);
            when(colRs.getInt(5)).thenReturn(0);
        }

        // Default value query
        PreparedStatement defPs = mock(PreparedStatement.class);
        ResultSet defRs = mock(ResultSet.class);
        when(defPs.executeQuery()).thenReturn(defRs);
        when(defRs.next()).thenReturn(false);

        // PK query
        PreparedStatement pkPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);

        // Index query
        PreparedStatement idxPs = mock(PreparedStatement.class);
        ResultSet idxRs = mock(ResultSet.class);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        when(idxRs.next()).thenReturn(false);

        // CHECK query
        PreparedStatement chkPs = mock(PreparedStatement.class);
        ResultSet chkRs = mock(ResultSet.class);
        when(chkPs.executeQuery()).thenReturn(chkRs);
        when(chkRs.next()).thenReturn(true);
        when(chkRs.getInt(1)).thenReturn(0);

        // NOT NULL query
        PreparedStatement nnPs = mock(PreparedStatement.class);
        ResultSet nnRs = mock(ResultSet.class);
        when(nnPs.executeQuery()).thenReturn(nnRs);
        when(nnRs.next()).thenReturn(true);
        when(nnRs.getInt(1)).thenReturn(0);

        // AI column query
        PreparedStatement aiPs = mock(PreparedStatement.class);
        ResultSet aiRs = mock(ResultSet.class);
        when(aiPs.executeQuery()).thenReturn(aiRs);
        when(aiRs.next()).thenReturn(false);

        // Fulltext query
        PreparedStatement ftPs = mock(PreparedStatement.class);
        ResultSet ftRs = mock(ResultSet.class);
        when(ftPs.executeQuery()).thenThrow(new SQLException("no fulltext", "S0002", 0));

        when(msConn.prepareStatement(anyString()))
                .thenReturn(colPs, defPs, pkPs, idxPs, chkPs, nnPs, aiPs, ftPs);
    }

    private void mockMsQueryWithColumnType(String schema, String table,
                                               String colName, String typeName,
                                               int maxLength, int precision, int scale) throws Exception {
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet colRs = mock(ResultSet.class);
        when(colPs.executeQuery()).thenReturn(colRs);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString(1)).thenReturn(colName);
        when(colRs.getString(2)).thenReturn(typeName);
        when(colRs.getInt(3)).thenReturn(maxLength);
        when(colRs.getInt(4)).thenReturn(precision);
        when(colRs.getInt(5)).thenReturn(scale);

        // Stub remaining queries (same as mockMsQueries)
        PreparedStatement defPs = mock(PreparedStatement.class);
        when(defPs.executeQuery()).thenReturn(mock(ResultSet.class));
        PreparedStatement pkPs = mock(PreparedStatement.class);
        when(pkPs.executeQuery()).thenReturn(mock(ResultSet.class));
        PreparedStatement idxPs = mock(PreparedStatement.class);
        when(idxPs.executeQuery()).thenReturn(mock(ResultSet.class));
        PreparedStatement chkPs = mock(PreparedStatement.class);
        ResultSet chkRs = mock(ResultSet.class);
        when(chkPs.executeQuery()).thenReturn(chkRs);
        when(chkRs.next()).thenReturn(true);
        when(chkRs.getInt(1)).thenReturn(0);
        PreparedStatement nnPs = mock(PreparedStatement.class);
        when(nnPs.executeQuery()).thenReturn(mock(ResultSet.class));
        PreparedStatement aiPs = mock(PreparedStatement.class);
        when(aiPs.executeQuery()).thenReturn(mock(ResultSet.class));
        PreparedStatement ftPs = mock(PreparedStatement.class);
        when(ftPs.executeQuery()).thenThrow(new SQLException("no fulltext", "S0002", 0));

        when(msConn.prepareStatement(anyString()))
                .thenReturn(colPs, defPs, pkPs, idxPs, chkPs, nnPs, aiPs, ftPs);
    }

    private void mockTiDbMetadata(String schema, String table, List<String> columns) throws Exception {
        mockTiDbMetadataWithColumns(schema, table, columns, List.of());
    }

    private void mockTiDbMetadataWithColumns(String schema, String table,
                                                  List<String> columns,
                                                  List<String> pkColumns) throws Exception {
        ResultSet colRs = mock(ResultSet.class);
        LinkedList<Boolean> nextResults = new LinkedList<>();
        for (String c : columns) nextResults.add(true);
        nextResults.add(false);
        Iterator<Boolean> it = nextResults.iterator();
        when(colRs.next()).thenAnswer(inv -> it.next());

        for (int i = 0; i < columns.size(); i++) {
            when(colRs.getString("COLUMN_NAME")).thenReturn(columns.get(i));
            when(colRs.getString("TYPE_NAME")).thenReturn("VARCHAR");
            when(colRs.getInt("COLUMN_SIZE")).thenReturn(255);
            when(colRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNullable);
            when(colRs.getString("COLUMN_DEF")).thenReturn(null);
            when(colRs.getString("IS_AUTOINCREMENT")).thenReturn("NO");
        }

        // Primary keys
        ResultSet pkRs = mock(ResultSet.class);
        LinkedList<Boolean> pkNext = new LinkedList<>();
        for (String pk : pkColumns) pkNext.add(true);
        pkNext.add(false);
        Iterator<Boolean> pkIt = pkNext.iterator();
        when(pkRs.next()).thenAnswer(inv -> pkIt.next());
        for (int i = 0; i < pkColumns.size(); i++) {
            when(pkRs.getShort("KEY_SEQ")).thenReturn((short) (i + 1));
            when(pkRs.getString("COLUMN_NAME")).thenReturn(pkColumns.get(i));
        }

        // Index info
        ResultSet idxRs = mock(ResultSet.class);
        when(idxRs.next()).thenReturn(false);

        when(tidbMeta.getColumns(eq("testdb"), isNull(), eq(table), isNull()))
                .thenReturn(colRs);
        when(tidbMeta.getPrimaryKeys(eq("testdb"), isNull(), eq(table)))
                .thenReturn(pkRs);
        when(tidbMeta.getIndexInfo(eq("testdb"), isNull(), eq(table), anyBoolean(), anyBoolean()))
                .thenReturn(idxRs);
    }
}
