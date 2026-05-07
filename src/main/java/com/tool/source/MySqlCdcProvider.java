package com.tool.source;

import java.sql.*;
import java.util.List;

/**
 * MySQL CDC provider: validates binary log configuration for Debezium snapshot/sync.
 */
public class MySqlCdcProvider implements CdcProvider {

    @Override
    public boolean isAgentRunning(Connection conn) {
        return true; // no agent concept in MySQL
    }

    @Override
    public boolean isCdcEnabled(Connection conn, String dbName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@log_bin")) {
            return rs.next() && "1".equals(rs.getString(1));
        }
    }

    @Override
    public List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                            List<String[]> tables) {
        return List.of(); // binlog captures all tables automatically
    }

    @Override
    public void enableCdc(Connection conn, String dbName) {
        throw new UnsupportedOperationException(
                "CDC cannot be enabled online for MySQL — set log_bin=ON in my.cnf and restart");
    }

    @Override
    public void enableCdcForTable(Connection conn, String dbName, String schema, String table) {
        throw new UnsupportedOperationException("Individual table CDC not applicable for MySQL");
    }

    @Override
    public CdcCheckResult check(Connection conn, String dbName, List<String[]> tables) {
        try {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@log_bin")) {
                if (!rs.next() || !"1".equals(rs.getString(1))) {
                    return new CdcCheckResult(true,
                            "MySQL binary logging is not enabled. " +
                            "Set log_bin=ON in my.cnf and restart MySQL.", false, false, List.of());
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@binlog_format")) {
                String fmt = rs.next() ? rs.getString(1) : "UNKNOWN";
                if (!"ROW".equalsIgnoreCase(fmt)) {
                    return new CdcCheckResult(true,
                            "MySQL binlog_format is '" + fmt + "', must be ROW for CDC. " +
                            "Set binlog_format=ROW in my.cnf and restart MySQL.", false, false, List.of());
                }
            }
            return new CdcCheckResult(false, null, true, true, List.of());
        } catch (Exception e) {
            return new CdcCheckResult(true, "CDC check failed: " + e.getMessage(), false, false, List.of());
        }
    }
}
