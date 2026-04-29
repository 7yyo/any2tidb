package com.tool.snapshot.cdc;

import com.tool.config.AppConfig;
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

public class CdcPreChecker {

    private static final Logger log = LoggerFactory.getLogger(CdcPreChecker.class);

    private final AppConfig.DbConfig source;

    public CdcPreChecker(AppConfig.DbConfig source) {
        this.source = source;
    }

    public record CdcCheckResult(
            boolean hasError,
            String errorMessage,
            boolean agentRunning,
            boolean cdcEnabled,
            List<String> tablesWithoutCdc
    ) {}

    public boolean isAgentRunning(Connection conn) throws Exception {
        String sql = "SELECT 1 FROM sys.dm_exec_sessions WHERE program_name LIKE 'SQLAgent%' AND status = 'running'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    public boolean isCdcEnabled(Connection conn, String dbName) throws Exception {
        String sql = "SELECT is_cdc_enabled FROM sys.databases WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    public List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                             List<String[]> tables) throws Exception {
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

    public void enableCdc(Connection conn, String dbName) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_db");
            log.info("[\"cdc enabled\"] [database={}]", dbName);
        }
    }

    public void enableCdcForTable(Connection conn, String dbName, String schema, String table) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_table @source_schema = '" + escapeQuote(schema)
                    + "', @source_name = '" + escapeQuote(table)
                    + "', @role_name = NULL");
            log.info("[\"cdc enabled\"] [database={}] [table={}.{}]", dbName, schema, table);
        }
    }

    private static String escapeQuote(String s) {
        return s.replace("'", "''");
    }

    public CdcCheckResult check(Connection conn, String dbName,
                                List<String[]> tables, boolean autoEnable) {
        try {
            boolean agentRunning = isAgentRunning(conn);
            if (!agentRunning) {
                log.warn("[\"SQL Server Agent is not running (not required for Debezium)\"]");
            }

            boolean cdcEnabled = isCdcEnabled(conn, dbName);
            if (!cdcEnabled) {
                if (autoEnable) {
                    enableCdc(conn, dbName);
                    cdcEnabled = true;
                } else {
                    return new CdcCheckResult(true,
                            "CDC is not enabled on database '" + dbName + "'. Use --enable-cdc or run: EXEC sys.sp_cdc_enable_db",
                            true, false, tables.stream().map(t -> t[1]).toList());
                }
            }

            List<String> missing = getTablesWithoutCdc(conn, dbName, tables);
            if (!missing.isEmpty()) {
                if (autoEnable) {
                    for (String[] t : tables) {
                        if (missing.contains(t[0] + "." + t[1])) {
                            enableCdcForTable(conn, dbName, t[0], t[1]);
                        }
                    }
                    missing = List.of();
                } else {
                    return new CdcCheckResult(true,
                            "CDC not enabled for tables: " + missing + ". Use --enable-cdc or run: EXEC sys.sp_cdc_enable_table",
                            true, true, missing);
                }
            }

            return new CdcCheckResult(false, null, true, true, List.of());
        } catch (Exception e) {
            return new CdcCheckResult(true, "CDC check failed: " + e.getMessage(), false, false,
                    tables.stream().map(t -> t[1]).toList());
        }
    }
}
