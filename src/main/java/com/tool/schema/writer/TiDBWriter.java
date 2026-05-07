package com.tool.schema.writer;

import com.tool.common.model.ConversionResult;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TiDBWriter implements SchemaWriter {

    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("^DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+`([^`]+)`", Pattern.CASE_INSENSITIVE);

    /**
     * Executes the full DDL string (may contain multiple statements separated by ";\n").
     *
     * If the DDL starts with DROP TABLE IF EXISTS, we first check whether the target
     * table already contains data. If it does, the entire DDL is skipped and a warning
     * is recorded — this prevents accidentally destroying existing rows.
     */
    public void executeDDL(Connection conn, String ddl, ConversionResult result) throws Exception {
        // Only split for multi-statement table DDL (DROP TABLE IF EXISTS ...;\nCREATE TABLE ...).
        // Functions, procedures, and triggers contain semicolons in their body and must be
        // executed as a single statement.
        boolean isMultiStatement = DROP_TABLE_PATTERN.matcher(ddl).find();
        String[] statements = isMultiStatement ? ddl.split(";\n", -1) : new String[]{ddl};

        if (isMultiStatement) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) continue;
                Matcher m = DROP_TABLE_PATTERN.matcher(sql);
                if (m.find()) {
                    String tableName = m.group(1);
                    if (tableHasData(conn, tableName)) {
                        result.setSkip("table `" + tableName + "` is not empty — DROP skipped, DDL not applied");
                        return;
                    }
                }
                break; // only inspect the first non-empty statement
            }
        }

        try (Statement stmt = conn.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) continue;
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            String msg = e.getMessage() != null
                    ? e.getMessage().replace('\n', ' ').replace('\r', ' ') : "null";
            result.setError(String.format("failed to execute DDL: [%s/%d] %s",
                    e.getSQLState(), e.getErrorCode(), msg));
        }
    }

    /**
     * Returns true if the table exists in TiDB and contains at least one row.
     * Returns false if the table does not exist or is empty.
     */
    private boolean tableHasData(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM `" + tableName + "` LIMIT 1")) {
            return rs.next();
        } catch (SQLException e) {
            // Table doesn't exist — safe to proceed with DROP + CREATE
            return false;
        }
    }
}
