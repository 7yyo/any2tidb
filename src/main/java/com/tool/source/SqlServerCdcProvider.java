package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SqlServerCdcProvider implements CdcProvider {

    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcProvider.class);
    private final AppConfig.DbConfig source;

    public SqlServerCdcProvider(AppConfig.DbConfig source) {
        this.source = source;
    }

    @Override
    public boolean isAgentRunning(Connection conn) throws Exception {
        String sql = "SELECT 1 FROM sys.dm_exec_sessions WHERE program_name LIKE 'SQLAgent%' AND status = 'running'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    @Override
    public boolean isCdcEnabled(Connection conn, String dbName) throws Exception {
        String sql = "SELECT is_cdc_enabled FROM sys.databases WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    @Override
    public List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                             List<String[]> tables) throws Exception {
        if (tables.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT s.name, t.name FROM cdc.change_tables ct " +
                "JOIN sys.tables t ON ct.source_object_id = t.object_id " +
                "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                "WHERE t.name IN (" +
                tables.stream().map(t -> "?").collect(Collectors.joining(",")) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < tables.size(); i++) {
                ps.setString(i + 1, tables.get(i)[1]);
            }
            Set<String> enabled = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    enabled.add(rs.getString(1) + "." + rs.getString(2));
                }
            }
            List<String> missing = new ArrayList<>();
            for (String[] t : tables) {
                String key = t[0] + "." + t[1];
                if (!enabled.contains(key)) {
                    missing.add(key);
                }
            }
            return missing;
        }
    }

    @Override
    public void enableCdc(Connection conn, String dbName) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_db");
            Log.info(log, "cdc enabled", "db", dbName);
        }
    }

    @Override
    public void enableCdcForTable(Connection conn, String dbName, String schema, String table) throws Exception {
        String captureName = sanitizeCaptureName(schema + "_" + table);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_table @source_schema = '" + escapeQuote(schema)
                    + "', @source_name = '" + escapeQuote(table)
                    + "', @role_name = NULL, @capture_instance = '" + escapeQuote(captureName) + "'");
            Log.info(log, "cdc enabled for table", "db", dbName, "table", schema + "." + table);
        } catch (java.sql.SQLException e) {
            // SQL Server error 22833: capture instance name already exists.
            // This happens when a table was dropped and recreated, leaving an orphaned
            // capture instance behind. Drop the orphan and retry.
            if (e.getMessage() != null && (e.getMessage().contains("already exists")
                    || e.getMessage().contains("已存在"))) {
                Log.warn(log, "orphaned capture instance exists, dropping and recreating",
                        "db", dbName, "captureInstance", captureName);
                try (Statement stmt2 = conn.createStatement()) {
                    stmt2.execute("EXEC sys.sp_cdc_drop_table @capture_instance = '" + escapeQuote(captureName) + "'");
                } catch (java.sql.SQLException e2) {
                    // If drop fails, try enable with explicit unique name as fallback
                    Log.warn(log, "drop orphan failed, trying with explicit capture name",
                            "db", dbName, "error", e2.getMessage());
                    String uniqueName = sanitizeCaptureName(captureName + "_" + System.currentTimeMillis());
                    try (Statement stmt3 = conn.createStatement()) {
                        stmt3.execute("EXEC sys.sp_cdc_enable_table @source_schema = '" + escapeQuote(schema)
                                + "', @source_name = '" + escapeQuote(table)
                                + "', @role_name = NULL, @capture_instance = '" + escapeQuote(uniqueName) + "'");
                    }
                    Log.info(log, "cdc enabled for table (custom name)", "db", dbName,
                            "table", schema + "." + table, "captureInstance", uniqueName);
                    return;
                }
                // Retry with default name after dropping orphan
                try (Statement stmt4 = conn.createStatement()) {
                    stmt4.execute("EXEC sys.sp_cdc_enable_table @source_schema = '" + escapeQuote(schema)
                            + "', @source_name = '" + escapeQuote(table)
                            + "', @role_name = NULL");
                }
                Log.info(log, "cdc enabled for table (recreated)", "db", dbName, "table", schema + "." + table);
            } else {
                throw e;
            }
        }
    }

    private static String escapeQuote(String s) {
        return s.replace("'", "''");
    }

    /**
     * Sanitize a capture instance name to only contain [a-zA-Z0-9_].
     * SQL Server capture instance names can't include special chars like (, ), [, ], etc.
     */
    private static String sanitizeCaptureName(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public CdcCheckResult check(Connection conn, String dbName,
                                List<String[]> tables) {
        try {
            try {
                isAgentRunning(conn);
            } catch (Exception e) {
                throw new Exception("Agent check failed: " + e.getMessage(), e);
            }

            try {
                if (!isCdcEnabled(conn, dbName)) {
                    enableCdc(conn, dbName);
                }
            } catch (Exception e) {
                throw new Exception("CDC enable for database failed: " + e.getMessage(), e);
            }

            List<String> missing;
            try {
                missing = getTablesWithoutCdc(conn, dbName, tables);
            } catch (Exception e) {
                throw new Exception("CDC table scan failed: " + e.getMessage(), e);
            }
            for (String[] t : tables) {
                if (missing.contains(t[0] + "." + t[1])) {
                    Log.info(log, "enabling CDC for table", "db", dbName, "table", t[0] + "." + t[1]);
                    try {
                        enableCdcForTable(conn, dbName, t[0], t[1]);
                    } catch (Exception e) {
                        throw new Exception("CDC check failed for " + t[0] + "." + t[1] + ": " + e.getMessage(), e);
                    }
                }
            }

            return new CdcCheckResult(false, null, true, true, List.of());
        } catch (Exception e) {
            return new CdcCheckResult(true, "CDC check failed: " + e.getMessage(), false, false,
                    tables.stream().map(t -> t[1]).toList());
        }
    }
}
