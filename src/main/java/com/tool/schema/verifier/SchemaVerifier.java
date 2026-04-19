package com.tool.schema.verifier;

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

        // 列名（按 column_id 有序）+ MS 侧列类型
        List<String> msColOrder = new ArrayList<>();
        Map<String, String> msColTypes = new LinkedHashMap<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name, t.name AS type_name, c.max_length, c.precision, c.scale " +
                "FROM sys.columns c " +
                "JOIN sys.objects o ON c.object_id = o.object_id " +
                "JOIN sys.types t ON c.user_type_id = t.user_type_id " +
                "WHERE o.name = ? AND o.schema_id = SCHEMA_ID(?) " +
                "ORDER BY c.column_id")) {
            ps.setString(1, tableName);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString(1);
                    msColOrder.add(colName);
                    String typeName  = rs.getString(2).toLowerCase();
                    int    maxLength = rs.getInt(3);
                    int    precision = rs.getInt(4);
                    int    scale     = rs.getInt(5);
                    String typeStr;
                    switch (typeName) {
                        case "nvarchar": case "nchar":
                            typeStr = maxLength == -1 ? typeName + "(max)" : typeName + "(" + (maxLength / 2) + ")";
                            break;
                        case "varchar": case "char": case "varbinary": case "binary":
                            typeStr = maxLength == -1 ? typeName + "(max)" : typeName + "(" + maxLength + ")";
                            break;
                        case "decimal": case "numeric":
                            typeStr = typeName + "(" + precision + "," + scale + ")";
                            break;
                        case "datetime2": case "time": case "datetimeoffset":
                            typeStr = typeName + "(" + scale + ")";
                            break;
                        default:
                            typeStr = typeName;
                    }
                    msColTypes.put(colName.toLowerCase(), typeStr);
                }
            }
        }
        int msCols = msColOrder.size();

        // 默认值（归一化后）
        Map<String, String> msDefaults = new LinkedHashMap<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT c.name, dc.definition " +
                "FROM sys.columns c " +
                "JOIN sys.objects o ON c.object_id = o.object_id " +
                "LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id " +
                "WHERE o.name = ? AND o.schema_id = SCHEMA_ID(?) " +
                "ORDER BY c.column_id")) {
            ps.setString(1, tableName);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString(1);
                    String rawDef  = rs.getString(2);
                    if (rawDef != null) {
                        msDefaults.put(colName, normalizeDefault(rawDef));
                    }
                }
            }
        }

        // 主键
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

        // 索引名（排除主键）：分为保留索引和已丢弃索引（COLUMNSTORE / FULLTEXT → type 4/5/6）
        Set<String> msIdxNames = new LinkedHashSet<>();
        Set<String> msDroppedIdxNames = new LinkedHashSet<>();
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT name, type FROM sys.indexes " +
                "WHERE object_id = OBJECT_ID(?) AND is_primary_key = 0 AND type > 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idxName = rs.getString(1);
                    int    idxType = rs.getInt(2);
                    // type 4 = NONCLUSTERED COLUMNSTORE, type 5 = CLUSTERED COLUMNSTORE
                    // type 6 = NONCLUSTERED HASH (not supported); FULLTEXT = handled separately
                    if (idxType == 4 || idxType == 5 || idxType == 6) {
                        msDroppedIdxNames.add(idxName);
                    } else {
                        msIdxNames.add(idxName);
                    }
                }
            }
        }
        // FULLTEXT indexes — also discarded during migration
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT i.name FROM sys.fulltext_indexes fi " +
                "JOIN sys.indexes i ON fi.unique_index_id = i.index_id AND fi.object_id = i.object_id " +
                "WHERE fi.object_id = OBJECT_ID(?)")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) msDroppedIdxNames.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
            // sys.fulltext_indexes may not exist on all editions; safe to skip
        }

        // CHECK 约束数
        int msChecks = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.check_constraints " +
                "WHERE parent_object_id = OBJECT_ID(?)")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msChecks = rs.getInt(1);
            }
        }

        // FK — discarded during migration, not verified
        // NOT NULL 数
        int msNotNull = 0;
        try (PreparedStatement ps = msConn.prepareStatement(
                "SELECT COUNT(*) FROM sys.columns " +
                "WHERE object_id = OBJECT_ID(?) AND is_nullable = 0")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) msNotNull = rs.getInt(1);
            }
        }

        // AUTO_INCREMENT 列
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
        String catalog = tidbConn.getCatalog();

        // 列名（按 ORDINAL_POSITION 有序）+ NOT NULL + AI + 默认值 + 类型
        int tidbCols = 0;
        List<String> tidbColOrder = new ArrayList<>();
        int tidbNotNull = 0;
        String tidbAiCol = null;
        Map<String, String> tidbDefaults = new LinkedHashMap<>();
        Map<String, String> tidbColTypes = new LinkedHashMap<>();

        try (ResultSet rs = meta.getColumns(catalog, null, tableName, null)) {
            while (rs.next()) {
                tidbCols++;
                String colName = rs.getString("COLUMN_NAME");
                tidbColOrder.add(colName);
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) tidbNotNull++;
                if ("YES".equals(rs.getString("IS_AUTOINCREMENT"))) tidbAiCol = colName;
                String colDef = rs.getString("COLUMN_DEF");
                if (colDef != null) {
                    tidbDefaults.put(colName.toLowerCase(), colDef.trim());
                }
                String typeName = rs.getString("TYPE_NAME");
                int colSize = rs.getInt("COLUMN_SIZE");
                // Build a readable type string, e.g. "VARCHAR(100)", "BIGINT", "DATETIME"
                String typeStr = typeName != null ? typeName.toUpperCase() : "UNKNOWN";
                if (colSize > 0 && (typeStr.startsWith("VARCHAR") || typeStr.startsWith("CHAR")
                        || typeStr.startsWith("NVARCHAR") || typeStr.startsWith("DECIMAL")
                        || typeStr.startsWith("NUMERIC") || typeStr.startsWith("DATETIME"))) {
                    typeStr = typeStr + "(" + colSize + ")";
                }
                tidbColTypes.put(colName.toLowerCase(), typeStr);
            }
        }

        // 主键
        List<String> tidbPkCols = new ArrayList<>();
        Map<Short, String> pkMap = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        tidbPkCols.addAll(pkMap.values());

        // 索引数
        Set<String> idxNames = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, null, tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && !name.equalsIgnoreCase("PRIMARY")) idxNames.add(name);
            }
        }
        int tidbIdx = idxNames.size();

        // CHECK 约束数（TiDB 8.0+ information_schema.TABLE_CONSTRAINTS）
        int tidbChecks = 0;
        try (PreparedStatement ps = tidbConn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
                "WHERE CONSTRAINT_TYPE = 'CHECK' AND TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, catalog);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) tidbChecks = rs.getInt(1);
            }
        } catch (SQLException ignored) {
            // TiDB < 8.0 不支持 CHECK，查询可能失败，tidbChecks 保持 0
        }

        return new VerifyResult(
                fullName,
                msCols, tidbCols,
                msColOrder, tidbColOrder,
                msPkCols, tidbPkCols,
                msIdxNames, msDroppedIdxNames, idxNames,
                msChecks, tidbChecks,
                msNotNull, tidbNotNull,
                msAiCol, tidbAiCol,
                msDefaults, tidbDefaults,
                msColTypes, tidbColTypes
        );
    }

    /**
     * 批量校验，返回每张表的 VerifyResult。
     */
    public List<VerifyResult> verifyAll(Connection msConn, Connection tidbConn,
                                        List<String[]> tableList) throws SQLException {
        List<VerifyResult> results = new ArrayList<>();
        for (String[] entry : tableList) {
            results.add(verify(msConn, tidbConn, entry[0], entry[1]));
        }
        return results;
    }

    /**
     * 对 SQL Server default_constraints.definition 做归一化，
     * 使其与 TiDB COLUMN_DEF 返回的格式尽量一致。
     * SS 的 definition 格式通常是 ((value)) 或 (value) 或 ('string')。
     */
    private String normalizeDefault(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        // 剥掉 SS 包裹的括号层
        while (v.startsWith("(") && v.endsWith(")")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        // Unicode 字符串字面量：N'val' → val（N'DEV' → DEV，N'' → ""，N'''' → '）
        if (v.startsWith("N'") && v.endsWith("'") && v.length() >= 3) {
            String inner = v.substring(2, v.length() - 1);
            return inner.replace("''", "'");
        }
        // 普通字符串字面量：SS 保留引号 'val'，TiDB JDBC COLUMN_DEF 返回去引号后的裸值
        // 例：SS='' → TiDB="" ; SS='CN' → TiDB=CN ; SS='''' → TiDB='
        if (v.startsWith("'") && v.endsWith("'")) {
            String inner = v.substring(1, v.length() - 1);
            // SQL Server escapes single-quotes by doubling them inside strings: '' → '
            return inner.replace("''", "'");
        }
        // 函数映射（与 TypeMapper.mapDefaultValue 保持一致）
        return switch (v.toUpperCase()) {
            case "GETDATE()"           -> "CURRENT_TIMESTAMP";
            case "GETUTCDATE()"        -> "UTC_TIMESTAMP()";
            case "NEWID()"             -> "UUID()";
            case "NEWSEQUENTIALID()"   -> "UUID()";
            case "SYSDATETIME()"       -> "CURRENT_TIMESTAMP";
            case "SYSDATETIMEOFFSET()" -> "CURRENT_TIMESTAMP";
            default                    -> v;
        };
    }
}
