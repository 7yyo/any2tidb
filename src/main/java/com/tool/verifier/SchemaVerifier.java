package com.tool.verifier;

import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class SchemaVerifier {

    /**
     * 对比单张表在 SQL Server 和 TiDB 中的 schema 指标。
     *
     * @param msConn    SQL Server 连接
     * @param tidbConn  TiDB 连接
     * @param schema    SQL Server schema 名，如 "dbo"
     * @param tableName 表名
     */
    public VerifyResult verify(Connection msConn, Connection tidbConn,
                               String schema, String tableName) throws SQLException {
        String fullName = schema + "." + tableName;
        String objectId = schema + "." + tableName;  // for OBJECT_ID(?)

        // ---- SQL Server 侧 ----
        int msCols = 0;
        Set<String> msColNames = new LinkedHashSet<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name FROM sys.columns c " +
                "JOIN sys.objects o ON c.object_id = o.object_id " +
                "WHERE o.name = ? AND o.schema_id = SCHEMA_ID(?) " +
                "ORDER BY c.column_id")) {
            ps.setString(1, tableName);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    msColNames.add(rs.getString(1));
                    msCols++;
                }
            }
        }

        List<String> msPkCols = new ArrayList<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name FROM sys.index_columns ic " +
                "JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id " +
                "JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id " +
                "WHERE i.is_primary_key = 1 " +
                "  AND i.object_id = OBJECT_ID(?) " +
                "ORDER BY ic.key_ordinal")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) msPkCols.add(rs.getString(1));
            }
        }

        int msIdx = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.indexes " +
                "WHERE object_id = OBJECT_ID(?) AND is_primary_key = 0 AND type > 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msIdx = rs.getInt(1);
            }
        }

        int msFk = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID(?)")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msFk = rs.getInt(1);
            }
        }

        int msNotNull = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.columns " +
                "WHERE object_id = OBJECT_ID(?) AND is_nullable = 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msNotNull = rs.getInt(1);
            }
        }

        String msAiCol = null;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT name FROM sys.columns WHERE object_id = OBJECT_ID(?) AND is_identity = 1")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msAiCol = rs.getString(1);
            }
        }

        // ---- TiDB 侧（JDBC DatabaseMetaData） ----
        DatabaseMetaData meta = tidbConn.getMetaData();
        // TiDB/MySQL uses catalog (= database name) to scope queries; null means all databases
        String catalog = tidbConn.getCatalog();

        int tidbCols = 0;
        Set<String> tidbColNames = new LinkedHashSet<>();
        int tidbNotNull = 0;
        String tidbAiCol = null;
        try (ResultSet rs = meta.getColumns(catalog, null, tableName, null)) {
            while (rs.next()) {
                tidbCols++;
                tidbColNames.add(rs.getString("COLUMN_NAME"));
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) tidbNotNull++;
                if ("YES".equals(rs.getString("IS_AUTOINCREMENT"))) tidbAiCol = rs.getString("COLUMN_NAME");
            }
        }

        List<String> tidbPkCols = new ArrayList<>();
        // getPrimaryKeys 结果无序，需按 KEY_SEQ 排序
        Map<Short, String> pkMap = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        tidbPkCols.addAll(pkMap.values());

        Set<String> idxNames = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, null, tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && !name.equalsIgnoreCase("PRIMARY")) idxNames.add(name);
            }
        }
        int tidbIdx = idxNames.size();

        return new VerifyResult(
                fullName,
                msCols, tidbCols,
                msColNames, tidbColNames,
                msPkCols, tidbPkCols,
                msIdx, tidbIdx,
                msFk,
                msNotNull, tidbNotNull,
                msAiCol, tidbAiCol
        );
    }

    /**
     * 批量校验，返回每张表的 VerifyResult。
     * tableList 格式与 App.java 中相同：String[]{schemaName, tableName}
     */
    public List<VerifyResult> verifyAll(Connection msConn, Connection tidbConn,
                                        List<String[]> tableList) throws SQLException {
        List<VerifyResult> results = new ArrayList<>();
        for (String[] entry : tableList) {
            results.add(verify(msConn, tidbConn, entry[0], entry[1]));
        }
        return results;
    }
}
