package com.tool.schema.verifier;

import java.sql.*;
import java.util.*;

public class MySqlSchemaVerifier implements SchemaVerifier {

    @Override
    public VerifyResult verify(Connection srcConn, Connection tidbConn,
                               String schema, String tableName) throws SQLException {
        String fullName = schema + "." + tableName;
        VerifyResult.Builder b = VerifyResult.builder(fullName);

        // Source columns
        List<String> srcCols = new ArrayList<>();
        try (PreparedStatement ps = srcConn.prepareStatement(
                "SELECT column_name FROM information_schema.COLUMNS " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) srcCols.add(rs.getString(1));
            }
        }

        // TiDB columns + auto-increment
        List<String> tidbCols = new ArrayList<>();
        String tidbAi = null;
        DatabaseMetaData meta = tidbConn.getMetaData();
        String catalog = tidbConn.getCatalog();
        try (ResultSet rs = meta.getColumns(catalog, null, tableName, null)) {
            while (rs.next()) {
                tidbCols.add(rs.getString("COLUMN_NAME"));
                if ("YES".equals(rs.getString("IS_AUTOINCREMENT"))) tidbAi = rs.getString("COLUMN_NAME");
            }
        }

        // TiDB PK
        List<String> tidbPk = new ArrayList<>();
        Map<Short, String> pkMap = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        tidbPk.addAll(pkMap.values());

        // MySQL PK — same INFORMATION_SCHEMA approach
        List<String> srcPk = new ArrayList<>();
        try (PreparedStatement ps = srcConn.prepareStatement(
                "SELECT column_name FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE table_schema = ? AND table_name = ? AND constraint_name = 'PRIMARY' " +
                "ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) srcPk.add(rs.getString(1));
            }
        }

        // MySQL auto-increment
        String srcAi = null;
        try (PreparedStatement ps = srcConn.prepareStatement(
                "SELECT column_name FROM information_schema.COLUMNS " +
                "WHERE table_schema = ? AND table_name = ? AND extra LIKE '%auto_increment%'")) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) srcAi = rs.getString(1);
            }
        }

        // Checks
        b.check("columns", srcCols.equals(tidbCols),
                "[" + String.join(", ", srcCols) + "]", "[" + String.join(", ", tidbCols) + "]");

        b.check("pk", srcPk.equals(tidbPk),
                "[" + String.join(", ", srcPk) + "]", "[" + String.join(", ", tidbPk) + "]");

        b.check("auto_increment", Objects.equals(srcAi, tidbAi),
                srcAi != null ? srcAi : "(none)", tidbAi != null ? tidbAi : "(none)");

        return b.build();
    }

    @Override
    public List<VerifyResult> verifyAll(Connection srcConn, Connection tidbConn,
                                        List<String[]> tableList) throws SQLException {
        List<VerifyResult> results = new ArrayList<>();
        for (String[] entry : tableList) {
            results.add(verify(srcConn, tidbConn, entry[0], entry[1]));
        }
        return results;
    }
}
