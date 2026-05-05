package com.tool.schema.extractor;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.TableSchema;
import com.tool.schema.converter.TypeMapper;

import java.sql.*;
import java.util.*;

public class MySqlExtractor implements SchemaExtractor {

    @Override
    public List<String> listDatabases(Connection conn) throws Exception {
        List<String> dbs = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.SCHEMATA " +
                "WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') " +
                "ORDER BY schema_name";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) dbs.add(rs.getString(1));
        }
        return dbs;
    }

    @Override
    public List<String[]> listTables(Connection conn, List<String> schemas, List<String> tables) throws Exception {
        List<String[]> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT table_schema, table_name FROM information_schema.TABLES WHERE table_type = 'BASE TABLE'");
        if (schemas != null && !schemas.isEmpty()) {
            sql.append(" AND table_schema IN (");
            for (int i = 0; i < schemas.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("'").append(schemas.get(i).replace("'", "''")).append("'");
            }
            sql.append(")");
        } else {
            // Limit to the current database (connection opened via buildJdbcUrlTo)
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                sql.append(" AND table_schema = '").append(catalog.replace("'", "''")).append("'");
            }
        }
        if (tables != null && !tables.isEmpty()) {
            sql.append(" AND table_name IN (");
            for (int i = 0; i < tables.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("'").append(tables.get(i).replace("'", "''")).append("'");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY table_schema, table_name");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) result.add(new String[]{rs.getString(1), rs.getString(2)});
        }
        return result;
    }

    @Override
    public TableSchema extractTable(Connection conn, String schema, String table) {
        throw new UnsupportedOperationException("MySQL uses generateCreateTableDDL, not extractTable");
    }

    @Override
    public List<String> getPrimaryKeyColumns(Connection conn, String schema, String table) {
        throw new UnsupportedOperationException("MySQL uses generateCreateTableDDL, not getPrimaryKeyColumns");
    }

    @Override
    public Map<String, Long> estimateRowCounts(Connection conn, List<String[]> tables) throws Exception {
        Map<String, Long> estimates = new LinkedHashMap<>();
        for (String[] t : tables) {
            String sql = "SELECT table_rows FROM information_schema.TABLES " +
                    "WHERE table_schema = ? AND table_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, t[0]);
                ps.setString(2, t[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) estimates.put(t[1], rs.getLong(1));
                }
            }
        }
        return estimates;
    }

    @Override
    public String generateCreateTableDDL(Connection conn, String schema, String table,
                                         TypeMapper typeMapper, ConversionResult result,
                                         boolean dropIfExists) throws Exception {
        String sql = "SHOW CREATE TABLE `" + escape(schema) + "`.`" + escape(table) + "`";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                String ddl = rs.getString(2);
                if (dropIfExists) {
                    ddl = "DROP TABLE IF EXISTS `" + escape(table) + "`;\n\n" + ddl;
                }
                return ddl;
            }
            result.setError("Table not found: " + schema + "." + table);
            return null;
        }
    }

    // ── Views ──────────────────────────────────────────────────────────────

    @Override
    public List<String> listViews(Connection conn, String database) throws Exception {
        List<String> views = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW FULL TABLES IN `" + escape(database) + "` WHERE Table_type = 'VIEW'")) {
            while (rs.next()) views.add(rs.getString(1));
        }
        return views;
    }

    @Override
    public String generateViewDDL(Connection conn, String database, String view,
                                  ConversionResult result) throws Exception {
        String sql = "SHOW CREATE VIEW `" + escape(database) + "`.`" + escape(view) + "`";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return cleanDdl(rs.getString(2));
            result.setError("View not found: " + database + "." + view);
            return null;
        }
    }

    // ── Procedures ─────────────────────────────────────────────────────────

    @Override
    public List<String> listProcedures(Connection conn, String database) throws Exception {
        List<String> procs = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW PROCEDURE STATUS WHERE Db = '" + escape(database) + "'")) {
            while (rs.next()) procs.add(rs.getString("Name"));
        }
        return procs;
    }

    @Override
    public String generateProcedureDDL(Connection conn, String database, String proc,
                                       ConversionResult result) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW CREATE PROCEDURE `" + escape(database) + "`.`" + escape(proc) + "`")) {
            if (rs.next()) return cleanDdl(rs.getString(3)); // "Create Procedure" column
            result.setError("Procedure not found: " + database + "." + proc);
            return null;
        }
    }

    // ── Functions ──────────────────────────────────────────────────────────

    @Override
    public List<String> listFunctions(Connection conn, String database) throws Exception {
        List<String> funcs = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW FUNCTION STATUS WHERE Db = '" + escape(database) + "'")) {
            while (rs.next()) funcs.add(rs.getString("Name"));
        }
        return funcs;
    }

    @Override
    public String generateFunctionDDL(Connection conn, String database, String func,
                                      ConversionResult result) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW CREATE FUNCTION `" + escape(database) + "`.`" + escape(func) + "`")) {
            if (rs.next()) return cleanDdl(rs.getString(3)); // "Create Function" column
            result.setError("Function not found: " + database + "." + func);
            return null;
        }
    }

    // ── Triggers ───────────────────────────────────────────────────────────

    @Override
    public List<String> listTriggers(Connection conn, String database) throws Exception {
        List<String> triggers = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW TRIGGERS FROM `" + escape(database) + "`")) {
            while (rs.next()) triggers.add(rs.getString("Trigger"));
        }
        return triggers;
    }

    @Override
    public String generateTriggerDDL(Connection conn, String database, String trigger,
                                     ConversionResult result) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SHOW CREATE TRIGGER `" + escape(database) + "`.`" + escape(trigger) + "`")) {
            if (rs.next()) return cleanDdl(rs.getString(3)); // "SQL Original Statement" column
            result.setError("Trigger not found: " + database + "." + trigger);
            return null;
        }
    }

    // ── DDL cleanup ──────────────────────────────────────────────────────────

    /** Strip MySQL-specific clauses (DEFINER, ALGORITHM, SQL SECURITY) so DDL runs on TiDB. */
    private static String cleanDdl(String ddl) {
        return ddl
                .replaceFirst("(?i)ALGORITHM=\\S+\\s+", "")
                .replaceFirst("(?i)DEFINER=`[^`]*`@`[^`]*`\\s+", "")
                .replaceFirst("(?i)SQL\\s+SECURITY\\s+\\S+\\s+", "");
    }

    // ── helper ─────────────────────────────────────────────────────────────

    private static String escape(String s) {
        return s.replace("`", "``");
    }
}
