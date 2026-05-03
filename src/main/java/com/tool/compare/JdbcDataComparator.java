package com.tool.compare;

import java.sql.*;
import java.util.*;

public class JdbcDataComparator implements DataComparator {

    @Override
    public ComparisonReport compare(Connection source, Connection target, ComparisonConfig config) {
        int total = 0, matched = 0, mismatched = 0, skipped = 0;
        long totalRowsSrc = 0, totalRowsTgt = 0;
        List<TableComparison> results = new ArrayList<>();

        try {
            String targetCatalog = config.targetCatalog() != null
                    ? config.targetCatalog() : config.catalog();

            List<String[]> tables = config.tables();
            if (tables.isEmpty()) {
                tables = discoverTables(source, config.catalog());
            }

            for (String[] st : tables) {
                String schema = st[0];
                String table = st[1];
                String fullName = (schema != null && !schema.isEmpty() ? schema + "." : "") + table;

                total++;

                if (!tableExists(target, targetCatalog, schema, table)) {
                    skipped++;
                    results.add(new TableComparison(fullName,
                            TableComparison.Status.SKIPPED, 0, 0,
                            List.of(), List.of(), List.of()));
                    continue;
                }

                List<String> srcPk = getPrimaryKeys(source, config.catalog(), schema, table);
                List<String> tgtPk = getPrimaryKeys(target, targetCatalog, schema, table);
                if (srcPk.isEmpty() || tgtPk.isEmpty()) {
                    skipped++;
                    results.add(new TableComparison(fullName,
                            TableComparison.Status.SKIPPED, 0, 0,
                            List.of(), List.of(), List.of()));
                    continue;
                }

                long srcCount = countRows(source, config.catalog(), schema, table);
                long tgtCount = countRows(target, targetCatalog, schema, table);
                totalRowsSrc += srcCount;
                totalRowsTgt += tgtCount;

                List<String> missing = new ArrayList<>();
                List<String> extra = new ArrayList<>();
                List<ColumnDiff> diffs = compareRows(
                        source, target,
                        config.catalog(), targetCatalog,
                        schema, table, srcPk,
                        config.batchSize(), config.maxMismatchRows(),
                        missing, extra);

                TableComparison.Status status;
                if (srcCount == tgtCount && missing.isEmpty() && extra.isEmpty() && diffs.isEmpty()) {
                    status = TableComparison.Status.MATCHED;
                    matched++;
                } else {
                    status = TableComparison.Status.MISMATCHED;
                    mismatched++;
                }

                results.add(new TableComparison(fullName, status,
                        srcCount, tgtCount, missing, extra, diffs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("comparison failed", e);
        }

        return new ComparisonReport(total, matched, mismatched, skipped,
                totalRowsSrc, totalRowsTgt, results);
    }

    private List<String[]> discoverTables(Connection conn, String catalog) throws SQLException {
        List<String[]> result = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        String actualCatalog = conn.getCatalog() != null ? conn.getCatalog() : catalog;
        try (ResultSet rs = meta.getTables(actualCatalog, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && isSystemSchema(schema)) continue;
                String table = rs.getString("TABLE_NAME");
                if (schema == null) schema = "";
                result.add(new String[]{schema, table});
            }
        }
        return result;
    }

    private static boolean isSystemSchema(String schema) {
        String s = schema.toUpperCase();
        return s.equals("INFORMATION_SCHEMA") || s.equals("PERFORMANCE_SCHEMA")
                || s.equals("SYS") || s.equals("MYSQL") || s.equals("CDC");
    }

    private boolean tableExists(Connection conn, String catalog, String schema,
                                String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String actualCatalog = conn.getCatalog() != null ? conn.getCatalog() : catalog;
        try (ResultSet rs = meta.getTables(actualCatalog, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private List<String> getPrimaryKeys(Connection conn, String catalog,
                                        String schema, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        String actualCatalog = conn.getCatalog() != null ? conn.getCatalog() : catalog;
        try (ResultSet rs = meta.getPrimaryKeys(actualCatalog, schema, table)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    private long countRows(Connection conn, String catalog, String schema,
                           String table) throws SQLException {
        String q = qualifiedName(catalog, schema, table);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private List<ColumnDiff> compareRows(
            Connection src, Connection tgt,
            String srcCatalog, String tgtCatalog,
            String schema, String table, List<String> pkCols,
            int batchSize, int maxDiffs,
            List<String> missingOut, List<String> extraOut) throws SQLException {

        List<ColumnDiff> diffs = new ArrayList<>();

        String srcTable = qualifiedName(srcCatalog, schema, table);
        String tgtTable = qualifiedName(tgtCatalog, schema, table);

        String orderBy = String.join(", ",
                pkCols.stream().map(c -> "\"" + c + "\"").toList());

        String srcSql = "SELECT * FROM " + srcTable + " ORDER BY " + orderBy;
        String tgtSql = "SELECT * FROM " + tgtTable + " ORDER BY " + orderBy;

        try (Statement srcSt = src.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY);
             Statement tgtSt = tgt.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY)) {

            srcSt.setFetchSize(batchSize);
            tgtSt.setFetchSize(batchSize);

            try (ResultSet srcRs = srcSt.executeQuery(srcSql);
                 ResultSet tgtRs = tgtSt.executeQuery(tgtSql)) {

                boolean srcHas = srcRs.next();
                boolean tgtHas = tgtRs.next();

                while (srcHas && tgtHas) {
                    int cmp = comparePk(srcRs, tgtRs, pkCols);
                    if (cmp < 0) {
                        if (missingOut.size() < maxDiffs) {
                            missingOut.add(formatPkValues(srcRs, pkCols));
                        }
                        srcHas = srcRs.next();
                    } else if (cmp > 0) {
                        if (extraOut.size() < maxDiffs) {
                            extraOut.add(formatPkValues(tgtRs, pkCols));
                        }
                        tgtHas = tgtRs.next();
                    } else {
                        List<ColumnDiff.Diff> colDiffs = compareColumns(srcRs, tgtRs);
                        if (!colDiffs.isEmpty() && diffs.size() < maxDiffs) {
                            diffs.add(new ColumnDiff(
                                    pkValuesMap(srcRs, pkCols), colDiffs));
                        }
                        srcHas = srcRs.next();
                        tgtHas = tgtRs.next();
                    }
                }

                while (srcHas && missingOut.size() < maxDiffs) {
                    missingOut.add(formatPkValues(srcRs, pkCols));
                    srcHas = srcRs.next();
                }
                while (tgtHas && extraOut.size() < maxDiffs) {
                    extraOut.add(formatPkValues(tgtRs, pkCols));
                    tgtHas = tgtRs.next();
                }
            }
        }
        return diffs;
    }

    private int comparePk(ResultSet src, ResultSet tgt, List<String> pkCols)
            throws SQLException {
        for (String col : pkCols) {
            Object sv = src.getObject(col);
            Object tv = tgt.getObject(col);
            int c = compareValues(sv, tv);
            if (c != 0) return c;
        }
        return 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable ca && b instanceof Comparable cb
                && a.getClass().equals(b.getClass())) {
            return ca.compareTo(cb);
        }
        return ValueNormalizer.normalize(a).compareTo(ValueNormalizer.normalize(b));
    }

    private List<ColumnDiff.Diff> compareColumns(ResultSet src, ResultSet tgt)
            throws SQLException {
        List<ColumnDiff.Diff> diffs = new ArrayList<>();
        ResultSetMetaData smd = src.getMetaData();
        int cols = smd.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String colName = smd.getColumnName(i);
            Object sv = src.getObject(i);
            Object tv;
            try { tv = tgt.getObject(i); }
            catch (SQLException e) { tv = null; }
            if (!valuesEqual(sv, tv)) {
                diffs.add(new ColumnDiff.Diff(colName,
                        ValueNormalizer.normalize(sv),
                        ValueNormalizer.normalize(tv)));
            }
        }
        return diffs;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return ValueNormalizer.normalize(a).equals(ValueNormalizer.normalize(b));
    }

    private String formatPkValues(ResultSet rs, List<String> pkCols) throws SQLException {
        return TableComparison.formatPk(pkValuesMap(rs, pkCols));
    }

    private Map<String, String> pkValuesMap(ResultSet rs, List<String> pkCols)
            throws SQLException {
        Map<String, String> pk = new LinkedHashMap<>();
        for (String col : pkCols) {
            pk.put(col, ValueNormalizer.normalize(rs.getObject(col)));
        }
        return pk;
    }

    private static String qualifiedName(String catalog, String schema, String table) {
        StringBuilder sb = new StringBuilder();
        if (schema != null && !schema.isEmpty()) {
            sb.append("\"").append(schema).append("\".");
        }
        sb.append("\"").append(table).append("\"");
        return sb.toString();
    }
}
