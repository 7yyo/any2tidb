package com.tool;

import com.tool.compare.*;

import java.sql.*;
import java.util.*;

/**
 * SQL Server vs TiDB data comparison CLI — uses JdbcDataComparator.
 */
public class CompareData {

    private static final String SS_URL = "jdbc:sqlserver://127.0.0.1:1433;encrypt=false;database=%s";
    private static final String SS_USER = "sa";
    private static final String SS_PASS = "test@123";

    private static final String TIDB_URL = "jdbc:mysql://127.0.0.1:4000/%s?rewriteBatchedStatements=true";
    private static final String TIDB_USER = "root";
    private static final String TIDB_PASS = "";

    public static void main(String[] args) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Class.forName("com.mysql.cj.jdbc.Driver");

        List<String> dbs = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlserver://127.0.0.1:1433;encrypt=false", SS_USER, SS_PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT name FROM sys.databases WHERE name NOT IN ('master','tempdb','model','msdb') "
                + "AND state_desc = 'ONLINE' ORDER BY name")) {
            while (rs.next()) dbs.add(rs.getString("name"));
        }

        DataComparator cmp = new JdbcDataComparator();

        for (String db : dbs) {
            try (Connection ss = DriverManager.getConnection(
                    String.format(SS_URL, db), SS_USER, SS_PASS);
                 Connection tidb = DriverManager.getConnection(
                    String.format(TIDB_URL, db), TIDB_USER, TIDB_PASS)) {

                ComparisonReport r = cmp.compare(ss, tidb,
                        new ComparisonConfig(db, null, List.of(), 5000, 50));

                System.out.printf("%n=== %s ===%n", db);
                System.out.printf("Tables: %d matched, %d mismatched, %d skipped%n",
                        r.matchedTables(), r.mismatchedTables(), r.skippedTables());
                System.out.printf("Rows:   src=%,d  tgt=%,d%n",
                        r.totalRowsSrc(), r.totalRowsTgt());

                for (TableComparison t : r.tables()) {
                    switch (t.status()) {
                        case MATCHED -> System.out.printf("[OK]   %s  %,d rows%n",
                                t.fullName(), t.rowCountSrc());
                        case MISMATCHED -> {
                            System.out.printf("[MIS]  %s  src=%,d  tgt=%,d%n",
                                    t.fullName(), t.rowCountSrc(), t.rowCountTgt());
                            for (String m : t.missingInTarget()) {
                                System.out.printf("       missing in TiDB: %s%n", m);
                            }
                            for (String e : t.extraInTarget()) {
                                System.out.printf("       extra in TiDB:   %s%n", e);
                            }
                            for (ColumnDiff d : t.columnDiffs()) {
                                System.out.printf("       %s:%n",
                                        TableComparison.formatPk(d.pkValues()));
                                for (ColumnDiff.Diff diff : d.diffs()) {
                                    System.out.printf("         %s: src=%s tgt=%s%n",
                                            diff.column(), diff.srcValue(), diff.tgtValue());
                                }
                            }
                        }
                        case SKIPPED -> System.out.printf("[SKIP] %s%n", t.fullName());
                    }
                }
            } catch (Exception e) {
                System.out.printf("[ERR]  %s: %s%n", db, e.getMessage());
            }
        }
    }
}
