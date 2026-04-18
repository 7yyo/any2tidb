package com.tool.extractor;

import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class SqlServerExtractor {

    /**
     * List all tables in the given schemas (or all schemas if schemasFilter is empty/null).
     * Returns list of [schemaName, tableName] pairs.
     */
    public List<String[]> listTables(Connection conn, List<String> schemasFilter, List<String> tablesFilter) throws SQLException {
        List<String[]> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT s.name AS schema_name, t.name AS table_name " +
            "FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id " +
            "WHERE t.type = 'U'"
        );
        if (schemasFilter != null && !schemasFilter.isEmpty()) {
            sql.append(" AND s.name IN (").append(placeholders(schemasFilter.size())).append(")");
        }
        if (tablesFilter != null && !tablesFilter.isEmpty()) {
            sql.append(" AND t.name IN (").append(placeholders(tablesFilter.size())).append(")");
        }
        sql.append(" ORDER BY s.name, t.name");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (schemasFilter != null) for (String s : schemasFilter) ps.setString(idx++, s);
            if (tablesFilter != null) for (String t : tablesFilter) ps.setString(idx++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{rs.getString("schema_name"), rs.getString("table_name")});
            }
        }
        return result;
    }

    public TableSchema extractTable(Connection conn, String schemaName, String tableName) throws SQLException {
        TableSchema table = new TableSchema();
        table.setSchemaName(schemaName);
        table.setTableName(tableName);
        table.setColumns(extractColumns(conn, schemaName, tableName));
        table.setPrimaryKeyColumns(extractPrimaryKey(conn, schemaName, tableName));
        table.setCheckConstraints(extractCheckConstraints(conn, schemaName, tableName));
        table.setUniqueConstraintColumns(extractUniqueConstraints(conn, schemaName, tableName));
        table.setIndexes(extractIndexes(conn, schemaName, tableName));
        table.setForeignKeyCount(countForeignKeys(conn, schemaName, tableName));
        table.setPartitioned(isPartitioned(conn, schemaName, tableName));
        return table;
    }

    private List<ColumnSchema> extractColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT c.name, tp.name AS type_name,
                   c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity,
                   dc.definition AS default_value
            FROM sys.columns c
            JOIN sys.types tp ON c.user_type_id = tp.user_type_id
            JOIN sys.tables t ON c.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
            WHERE s.name = ? AND t.name = ?
            ORDER BY c.column_id
            """;
        List<ColumnSchema> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnSchema col = new ColumnSchema();
                    col.setName(rs.getString("name"));
                    col.setSqlServerType(rs.getString("type_name"));
                    int maxLen = rs.getInt("max_length");
                    col.setMaxLength(maxLen == 0 ? null : maxLen);
                    int prec = rs.getInt("precision");
                    col.setPrecision(prec == 0 ? null : prec);
                    int scale = rs.getInt("scale");
                    col.setScale(scale == 0 ? null : scale);
                    col.setNullable(rs.getBoolean("is_nullable"));
                    col.setIdentity(rs.getBoolean("is_identity"));
                    col.setDefaultValue(rs.getString("default_value"));
                    cols.add(col);
                }
            }
        }
        return cols;
    }

    private List<String> extractPrimaryKey(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT c.name
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE i.is_primary_key = 1 AND s.name = ? AND t.name = ?
            ORDER BY ic.key_ordinal
            """;
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private List<String> extractCheckConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT cc.definition
            FROM sys.check_constraints cc
            JOIN sys.tables t ON cc.parent_object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
            """;
        List<String> checks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) checks.add(rs.getString("definition"));
            }
        }
        return checks;
    }

    private List<String> extractUniqueConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT i.name AS idx_name,
                   STRING_AGG(c.name, ',') WITHIN GROUP (ORDER BY ic.key_ordinal) AS cols
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE i.is_unique_constraint = 1 AND s.name = ? AND t.name = ?
            GROUP BY i.name
            """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("cols"));
            }
        }
        return result;
    }

    private List<IndexSchema> extractIndexes(Connection conn, String schema, String table) throws SQLException {
        String idxSql = """
            SELECT i.name, i.is_unique, i.type_desc,
                   i.filter_definition,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM sys.fulltext_indexes fi WHERE fi.object_id = i.object_id
                   ) THEN 1 ELSE 0 END AS is_fulltext
            FROM sys.indexes i
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
              AND i.is_primary_key = 0 AND i.is_unique_constraint = 0
              AND i.type > 0
            """;
        String colSql = """
            SELECT c.name, ic.is_included_column
            FROM sys.index_columns ic
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ? AND i.name = ?
            ORDER BY ic.is_included_column, ic.key_ordinal
            """;
        List<IndexSchema> indexes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(idxSql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexSchema idx = new IndexSchema();
                    idx.setName(rs.getString("name"));
                    idx.setUnique(rs.getBoolean("is_unique"));
                    String typeDesc = rs.getString("type_desc");
                    idx.setClustered("CLUSTERED".equalsIgnoreCase(typeDesc));
                    idx.setColumnstore(typeDesc != null && typeDesc.toUpperCase().contains("COLUMNSTORE"));
                    idx.setFulltext(rs.getInt("is_fulltext") == 1);
                    idx.setFilterDefinition(rs.getString("filter_definition"));

                    List<String> keyCols = new ArrayList<>(), includeCols = new ArrayList<>();
                    try (PreparedStatement ps2 = conn.prepareStatement(colSql)) {
                        ps2.setString(1, schema); ps2.setString(2, table); ps2.setString(3, idx.getName());
                        try (ResultSet rs2 = ps2.executeQuery()) {
                            while (rs2.next()) {
                                if (rs2.getBoolean("is_included_column")) includeCols.add(rs2.getString("name"));
                                else keyCols.add(rs2.getString("name"));
                            }
                        }
                    }
                    idx.setColumns(keyCols);
                    idx.setIncludeColumns(includeCols);
                    indexes.add(idx);
                }
            }
        }
        return indexes;
    }

    private int countForeignKeys(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM sys.foreign_keys fk
            JOIN sys.tables t ON fk.parent_object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean isPartitioned(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM sys.partitions p
            JOIN sys.tables t ON p.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ? AND p.partition_number > 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
