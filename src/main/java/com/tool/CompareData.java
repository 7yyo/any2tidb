package com.tool;

import java.sql.*;
import java.util.*;

/**
 * Quick SQL Server ↔ TiDB data comparison — ad-hoc tool, not part of the product.
 */
public class CompareData {

    private static final String SS_URL = "jdbc:sqlserver://127.0.0.1:1433;encrypt=false;database=%s";
    private static final String SS_USER = "sa";
    private static final String SS_PASS = "test@123";

    private static final String TIDB_URL = "jdbc:mysql://127.0.0.1:4000/%s?rewriteBatchedStatements=true";
    private static final String TIDB_USER = "root";
    private static final String TIDB_PASS = "";

    // Tables to skip (large perf tables)
    private static final Set<String> SKIP_TABLES = Set.of("perf_test", "perf_test_10m");

    public static void main(String[] args) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Class.forName("com.mysql.cj.jdbc.Driver");

        // Discover SQL Server databases
        Map<String, List<String[]>> dbTables = new LinkedHashMap<>(); // dbName → [schema,table]
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlserver://127.0.0.1:1433;encrypt=false", SS_USER, SS_PASS);
             Statement s = c.createStatement()) {

            ResultSet rs = s.executeQuery(
                "SELECT name FROM sys.databases WHERE name NOT IN ('master','tempdb','model','msdb') AND state_desc = 'ONLINE' ORDER BY name");
            List<String> dbs = new ArrayList<>();
            while (rs.next()) dbs.add(rs.getString("name"));

            for (String db : dbs) {
                try (Connection dc = DriverManager.getConnection(
                        String.format(SS_URL, db), SS_USER, SS_PASS)) {
                    List<String[]> tables = new ArrayList<>();
                    try (PreparedStatement ps = dc.prepareStatement(
                            "SELECT s.name, t.name FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE t.type = 'U' AND t.is_ms_shipped = 0 AND s.name NOT IN ('sys', 'INFORMATION_SCHEMA', 'cdc') ORDER BY s.name, t.name")) {
                        ResultSet trs = ps.executeQuery();
                        while (trs.next()) tables.add(new String[]{trs.getString(1), trs.getString(2)});
                    }
                    if (!tables.isEmpty()) dbTables.put(db, tables);
                }
            }
        }

        int compared = 0, matched = 0, mismatched = 0, skipped = 0;

        for (var entry : dbTables.entrySet()) {
            String db = entry.getKey();
            for (String[] st : entry.getValue()) {
                String schema = st[0], table = st[1];
                if (SKIP_TABLES.contains(table)) {
                    skipped++;
                    continue;
                }

                // Row counts
                long ssRows;
                try (Connection c = DriverManager.getConnection(
                        String.format(SS_URL, db), SS_USER, SS_PASS);
                     Statement s = c.createStatement()) {
                    ResultSet rs = s.executeQuery(
                        "SELECT COUNT(*) FROM [" + schema + "].[" + table + "]");
                    rs.next();
                    ssRows = rs.getLong(1);
                }

                long tidbRows;
                try (Connection c = DriverManager.getConnection(
                        String.format(TIDB_URL, db), TIDB_USER, TIDB_PASS);
                     Statement s = c.createStatement()) {
                    ResultSet rs = s.executeQuery(
                        "SELECT COUNT(*) FROM `" + db + "`.`" + table + "`");
                    rs.next();
                    tidbRows = rs.getLong(1);
                } catch (Exception e) {
                    System.out.printf("[ERR]  %s.%s.%s  TiDB: %s%n", db, schema, table, e.getMessage());
                    mismatched++;
                    continue;
                }

                compared++;
                if (ssRows == tidbRows) {
                    System.out.printf("[OK]   %s.%s.%s  %d rows%n", db, schema, table, ssRows);
                    matched++;
                } else {
                    System.out.printf("[MIS]  %s.%s.%s  SS=%d  TiDB=%d  diff=%d%n",
                            db, schema, table, ssRows, tidbRows, ssRows - tidbRows);
                    mismatched++;

                    // If small table and mismatched, show some sample data
                    if (ssRows <= 10) {
                        dumpSamples(db, schema, table, ssRows);
                    }
                }
            }
        }

        System.out.println();
        System.out.printf("Compared: %d  Matched: %d  Mismatched: %d  Skipped: %d%n",
                compared, matched, mismatched, skipped);
    }

    private static void dumpSamples(String db, String schema, String table, long ssRows)
            throws Exception {
        System.out.println("  --- SQL Server ---");
        try (Connection c = DriverManager.getConnection(
                String.format(SS_URL, db), SS_USER, SS_PASS);
             Statement s = c.createStatement()) {
            // Get column names
            ResultSet rs = s.executeQuery("SELECT TOP 5 * FROM [" + schema + "].[" + table + "]");
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    row.add(md.getColumnName(i) + "=" + rs.getObject(i));
                }
                System.out.println("    " + row);
            }
        }

        System.out.println("  --- TiDB ---");
        try (Connection c = DriverManager.getConnection(
                String.format(TIDB_URL, db), TIDB_USER, TIDB_PASS);
             Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT * FROM `" + db + "`.`" + table + "` LIMIT 5");
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    row.add(md.getColumnName(i) + "=" + rs.getObject(i));
                }
                System.out.println("    " + row);
            }
        }
    }
}
