package com.tool.writer;

import com.tool.ConversionResult;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class TiDBWriter {

    /**
     * Executes the full DDL string (may contain multiple statements separated by semicolons).
     * Each statement is executed individually.
     */
    public void executeDDL(Connection conn, String ddl, ConversionResult result) {
        String[] statements = ddl.split(";");
        try (Statement stmt = conn.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) continue;
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            result.setError("failed to execute DDL: " + e.getMessage());
        }
    }

    /**
     * Prints DDL to stdout without executing (dry-run mode).
     */
    public void printDDL(String tableName, String ddl) {
        System.out.println("-- DDL for " + tableName);
        System.out.println(ddl);
    }
}
