package com.tool.snapshot.cdc;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for CdcPreChecker — targets SQL injection in
 * enableCdcForTable, edge cases in CDC checks, and error handling.
 */
class CdcPreCheckerAdversarialTest {

    private CdcPreChecker checker;
    private Connection conn;
    private AppConfig.DbConfig source;

    @BeforeEach
    void setUp() {
        source = new AppConfig.DbConfig();
        checker = new CdcPreChecker(source);
        conn = mock(Connection.class);
    }

    // ── enableCdcForTable: SQL injection via table name ───────────────────

    @Test
    void enableCdcForTable_tableNameWithQuote_escaped() throws Exception {
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        checker.enableCdcForTable(conn, "mydb", "dbo", "my'); DROP TABLE users--");

        verify(stmt).execute(argThat(sql ->
                sql.contains("my''); DROP TABLE users--")));
    }

    @Test
    void enableCdcForTable_schemaNameWithQuote_escaped() throws Exception {
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        checker.enableCdcForTable(conn, "mydb", "my'); DROP SCHEMA--", "t");

        verify(stmt).execute(argThat(sql ->
                sql.contains("my''); DROP SCHEMA--")));
    }

    // ── enableCdcForTable: table name with special chars ────────────────────────

    @Test
    void enableCdcForTable_tableNameWithDotSafe() throws Exception {
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        checker.enableCdcForTable(conn, "mydb", "dbo", "my.table");

        verify(stmt).execute(argThat(sql ->
                sql.contains("my.table") && !sql.contains(";")));
    }

    // ── isAgentRunning: LIKE pattern edge case ───────────────────────────────

    @Test
    void isAgentRunning_noAgentSessions_returnsFalse() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertFalse(checker.isAgentRunning(conn));
    }

    // ── isCdcEnabled: database not found ────────────────────────────────────

    @Test
    void isCdcEnabled_databaseNotFound_returnsFalse() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertFalse(checker.isCdcEnabled(conn, "nonexistent_db"));
    }

    // ── getTablesWithoutCdc: empty table list ────────────────────────────────

    @Test
    void getTablesWithoutCdc_emptyList_returnsEmpty() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        // Also need count query
        PreparedStatement cntPs = mock(PreparedStatement.class);
        ResultSet cntRs = mock(ResultSet.class);
        when(cntPs.executeQuery()).thenReturn(cntRs);
        when(cntRs.next()).thenReturn(true);
        when(cntRs.getInt(1)).thenReturn(0);

        List<String> missing = checker.getTablesWithoutCdc(conn, "mydb", List.of());
        assertTrue(missing.isEmpty());
    }

    // ── check: SQLException propagates as error result ───────────────────────

    @Test
    void check_sqlException_returnsErrorResult() {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString()))
                .thenThrow(new SQLException("permission denied", "42000", 0));

        CdcPreChecker.CdcCheckResult r = checker.check(conn, "mydb",
                List.of(new String[]{"dbo", "t1"}), false);

        assertTrue(r.hasError());
        assertTrue(r.errorMessage().contains("permission denied"));
    }

    // ── check: auto-enable CDC when not enabled ───────────────────────────────

    @Test
    void check_autoEnableCdc_enablesDb() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        Statement stmt = mock(Statement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // isCdcEnabled = false
        when(conn.createStatement()).thenReturn(stmt);

        CdcPreChecker.CdcCheckResult r = checker.check(conn, "mydb",
                List.of(new String[]{"dbo", "t1"}), true);

        assertFalse(r.hasError());
        // enableCdc was called
        verify(stmt).execute(contains("sp_cdc_enable_db"));
    }

    // ── check: auto-enable CDC for tables ─────────────────────────────────────

    @Test
    void check_autoEnableCdcForTables_enablesEachTable() throws Exception {
        // isCdcEnabled = true (already enabled at DB level)
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        Statement stmt = mock(Statement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(false); // one table has CDC
        when(conn.createStatement()).thenReturn(stmt);

        CdcPreChecker.CdcCheckResult r = checker.check(conn, "mydb",
                List.of(new String[]{"dbo", "t1"}, new String[]{"dbo", "t2"}}), true);

        // t2 was not in CDC result, so enableCdcForTable should be called for t2
        verify(stmt).execute(argThat(sql ->
                sql.contains("sp_cdc_enable_table") && sql.contains("t2")));
    }
}
