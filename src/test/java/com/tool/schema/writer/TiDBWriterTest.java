package com.tool.schema.writer;

import com.tool.common.model.ConversionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TiDBWriterTest {

    private TiDBWriter writer;
    private Connection conn;
    private Statement stmt;

    @BeforeEach
    void setUp() throws Exception {
        writer = new TiDBWriter();
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
    }

    // ── tableHasData() is private; we test it indirectly through executeDDL ──

    /**
     * DDL starts with DROP TABLE IF EXISTS — if table already has data,
     * executeDDL must skip execution and record a warning.
     */
    @Test
    void executeDDL_withDropAndDataPresent_skipsAndWarns() throws Exception {
        // Arrange: SELECT 1 FROM `users` LIMIT 1 → returns a row (table has data)
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = "DROP TABLE IF EXISTS `users`;\n\nCREATE TABLE `users` (`id` INT NOT NULL);";

        // Act
        writer.executeDDL(conn, ddl, result);

        // Assert: execute() must NOT have been called (no DDL applied)
        verify(stmt, never()).execute(anyString());
        // A warning must have been recorded
        assertTrue(result.getStatus() == ConversionResult.Status.SKIP
                        && result.getErrorMessage() != null
                        && result.getErrorMessage().contains("is not empty"),
                "Expected data-protection skip, got status=" + result.getStatus()
                        + " msg=" + result.getErrorMessage());
    }

    /**
     * DDL starts with DROP TABLE IF EXISTS — table is empty → proceed normally.
     */
    @Test
    void executeDDL_withDropAndEmptyTable_executes() throws Exception {
        // Arrange: SELECT 1 FROM `users` LIMIT 1 → no rows (table is empty)
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = "DROP TABLE IF EXISTS `users`;\n\nCREATE TABLE `users` (`id` INT NOT NULL);";

        // Act
        writer.executeDDL(conn, ddl, result);

        // Assert: execute() called for both statements
        verify(stmt, times(2)).execute(anyString());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("is not empty")));
    }

    /**
     * DDL starts with DROP TABLE IF EXISTS — table does not exist (SQLException on SELECT)
     * → treated as safe, proceed with DDL.
     */
    @Test
    void executeDDL_withDropAndTableNotExist_executes() throws Exception {
        // Arrange: SELECT throws (table does not exist)
        when(stmt.executeQuery(anyString())).thenThrow(new SQLException("Table not found", "42S02", 1146));

        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = "DROP TABLE IF EXISTS `users`;\n\nCREATE TABLE `users` (`id` INT NOT NULL);";

        // Act
        writer.executeDDL(conn, ddl, result);

        // Assert: DDL should proceed
        verify(stmt, times(2)).execute(anyString());
        assertTrue(result.getWarnings().isEmpty() ||
                result.getWarnings().stream().noneMatch(w -> w.contains("already contains data")));
    }

    /**
     * DDL without DROP TABLE — no data check, statements execute directly.
     */
    @Test
    void executeDDL_withoutDrop_executesDirectly() throws Exception {
        ConversionResult result = new ConversionResult("dbo.orders");
        String ddl = "CREATE TABLE `orders` (`id` INT NOT NULL);\nCREATE INDEX `idx` ON `orders` (`id`);";

        writer.executeDDL(conn, ddl, result);

        verify(stmt, times(2)).execute(anyString());
        verify(stmt, never()).executeQuery(anyString());
        assertEquals(ConversionResult.Status.OK, result.getStatus());
    }

    /**
     * If execute() throws Exception, the error is recorded in the result.
     */
    @Test
    void executeDDL_sqlException_setsError() throws Exception {
        ConversionResult result = new ConversionResult("dbo.orders");
        String ddl = "CREATE TABLE `orders` (`id` INT NOT NULL);";
        doThrow(new SQLException("Syntax error", "42000", 1064))
                .when(stmt).execute(anyString());

        writer.executeDDL(conn, ddl, result);

        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().contains("failed to execute DDL"));
    }
}
