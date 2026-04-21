package com.tool.snapshot.cdc;

import com.tool.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CdcPreCheckerTest {

    private AppConfig.DbConfig sourceConfig;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        sourceConfig = new AppConfig.DbConfig();
        sourceConfig.setHost("127.0.0.1");
        sourceConfig.setPort(1433);
        sourceConfig.setUsername("sa");
        sourceConfig.setPassword("pw");
        conn = mock(Connection.class);
    }

    @Test
    void agentRunning_returnsTrue() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertTrue(checker.isAgentRunning(conn));
    }

    @Test
    void agentNotRunning_returnsFalse() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertFalse(checker.isAgentRunning(conn));
    }

    @Test
    void isCdcEnabled_returnsTrue() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(1);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertTrue(checker.isCdcEnabled(conn, "testdb"));
    }

    @Test
    void isCdcEnabled_returnsFalse() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertFalse(checker.isCdcEnabled(conn, "testdb"));
    }

    @Test
    void getTablesWithoutCdc_returnsEmptyWhenAllEnabled() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("Users", "Orders");
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        List<String[]> tables = List.of(new String[]{"dbo", "Users"}, new String[]{"dbo", "Orders"});
        List<String> missing = checker.getTablesWithoutCdc(conn, "testdb", tables);
        assertTrue(missing.isEmpty());
    }

    @Test
    void getTablesWithoutCdc_returnsMissingTables() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Users");
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        List<String[]> tables = List.of(new String[]{"dbo", "Users"}, new String[]{"dbo", "Orders"});
        List<String> missing = checker.getTablesWithoutCdc(conn, "testdb", tables);
        assertEquals(1, missing.size());
        assertEquals("Orders", missing.get(0));
    }

    @Test
    void checkResult_hasErrorWhenAgentNotRunning() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        CdcPreChecker.CdcCheckResult result = checker.check(conn, "testdb",
                List.<String[]>of(new String[]{"dbo", "Users"}), false);

        assertTrue(result.hasError());
        assertNotNull(result.errorMessage());
    }
}
