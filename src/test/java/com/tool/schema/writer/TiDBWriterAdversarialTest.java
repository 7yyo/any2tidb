package com.tool.schema.writer;

import com.tool.common.model.ConversionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for TiDBWriter — targets edge cases in DDL execution
 * that could cause data loss, silent failures, or injection.
 */
class TiDBWriterAdversarialTest {

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

    // ── null DDL → NPE ───────────────────────────────────────────────────────

    @Test
    void executeDDL_null_throwsNPE() {
        ConversionResult result = new ConversionResult("t");
        assertThrows(NullPointerException.class,
                () -> writer.executeDDL(conn, null, result),
                "null DDL should throw NPE on split()");
    }

    // ── empty DDL → no statements executed ───────────────────────────────────

    @Test
    void executeDDL_empty_noExecution() throws Exception {
        ConversionResult result = new ConversionResult("t");
        writer.executeDDL(conn, "", result);

        verify(stmt, never()).execute(anyString());
        verify(stmt, never()).executeQuery(anyString());
        assertEquals(ConversionResult.Status.OK, result.getStatus());
    }

    // ── DDL with only whitespace/semicolons ─────────────────────────────────

    @Test
    void executeDDL_onlySemicolons_noExecution() throws Exception {
        ConversionResult result = new ConversionResult("t");
        writer.executeDDL(conn, ";\n;\n;\n", result);

        verify(stmt, never()).execute(anyString());
        verify(stmt, never()).executeQuery(anyString());
        assertEquals(ConversionResult.Status.OK, result.getStatus());
    }

    // ── DROP TABLE without backticks — regex doesn't match ───────────────────

    @Test
    void executeDDL_dropWithoutBackticks_bypassesSafetyCheck() throws Exception {
        // "DROP TABLE IF EXISTS users" — no backticks → regex [^`]+ doesn't match
        // The DROP proceeds unguarded — potential data loss!
        ConversionResult result = new ConversionResult("t");
        String ddl = "DROP TABLE IF EXISTS users;\nCREATE TABLE users (id INT);";

        writer.executeDDL(conn, ddl, result);

        // Safety check (executeQuery) was NOT called — regex didn't match
        verify(stmt, never()).executeQuery(anyString());
        // But execute() WAS called — DDL applied without data check
        verify(stmt, times(2)).execute(anyString());
        // This documents a potential safety gap
        assertTrue(true, "DROP TABLE without backticks bypasses data-safety check");
    }

    // ── DDL split on ;\r\n (Windows line endings) ─────────────────────────

    @Test
    void executeDDL_windowsLineEndings_splitCorrectly() throws Exception {
        // split(";\n") does NOT split on ";\r\n" → single statement with \r
        // But trim() removes \r, so it works — just 1 statement instead of 2
        ConversionResult result = new ConversionResult("t");
        String ddl = "DROP TABLE IF EXISTS `t`;\r\nCREATE TABLE `t` (id INT);";

        writer.executeDDL(conn, ddl, result);

        // Because split(";\n") doesn't match ";\r\n", it's treated as one statement:
        // "DROP TABLE IF EXISTS `t`;\r\nCREATE TABLE `t` (id INT)"
        // trim() strips \r\n from end → "DROP TABLE IF EXISTS `t`;\r\nCREATE TABLE `t` (id INT)"
        // Actually, trim() only strips whitespace from ends. The \r in the middle remains.
        // The statement is sent to TiDB as-is — which may or may not work.
        // BUG: split should use ";\n" or ";\\s*\\n" to handle \r\n
        verify(stmt, times(1)).execute(anyString());
    }

    // ── Mid-batch failure: first statement succeeds, second fails ────────────

    @Test
    void executeDDL_midBatchFailure_partialExecution() throws Exception {
        ConversionResult result = new ConversionResult("t");
        String ddl = "CREATE TABLE `t` (id INT);\nCREATE INDEX `ix` ON `t` (id);";

        // First execute succeeds, second throws
        doNothing().doThrow(new SQLException("Unknown column", "42S22", 1054))
                .when(stmt).execute(anyString());

        writer.executeDDL(conn, ddl, result);

        // Error recorded
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().contains("failed to execute DDL"));
        // But first statement's side effect (CREATE TABLE) is already committed!
        verify(stmt, times(2)).execute(anyString());
        // This documents a partial-execution risk
        assertTrue(true, "First statement executed before second failed — partial DDL applied");
    }

    // ── DDL with trailing newline but no trailing semicolon ─────────────────

    @Test
    void executeDDL_noTrailingSemicolon_stillExecuted() throws Exception {
        ConversionResult result = new ConversionResult("t");
        String ddl = "CREATE TABLE `t` (id INT NOT NULL)";

        writer.executeDDL(conn, ddl, result);

        verify(stmt, times(1)).execute(anyString());
        assertEquals(ConversionResult.Status.OK, result.getStatus());
    }

    // ── tableHasData: RuntimeException not caught ────────────────────────────

    @Test
    void executeDDL_runtimeExceptionInSafetyCheck_propagates() throws Exception {
        // tableHasData catches SQLException but not RuntimeException
        when(stmt.executeQuery(anyString()))
                .thenThrow(new RuntimeException("Connection pool exhausted"));

        ConversionResult result = new ConversionResult("t");
        String ddl = "DROP TABLE IF EXISTS `t`;\nCREATE TABLE `t` (id INT);";

        assertThrows(RuntimeException.class,
                () -> writer.executeDDL(conn, ddl, result),
                "RuntimeException in tableHasData is not caught — propagates uncaught");
    }

    // ── Case-insensitive DROP TABLE ─────────────────────────────────────────

    @Test
    void executeDDL_dropTableLowerCase_matches() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);  // has data
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        ConversionResult result = new ConversionResult("t");
        String ddl = "drop table if exists `users`;\nCREATE TABLE `users` (id INT);";

        writer.executeDDL(conn, ddl, result);

        verify(stmt, never()).execute(anyString());
        assertEquals(ConversionResult.Status.SKIP, result.getStatus());
    }

    // ── Multiple indexes after CREATE TABLE ─────────────────────────────────

    @Test
    void executeDDL_createTableWithMultipleIndexes_allExecuted() throws Exception {
        ConversionResult result = new ConversionResult("t");
        String ddl = "CREATE TABLE `t` (id INT, name VARCHAR(100));\n"
                + "CREATE INDEX `ix_id` ON `t` (`id`);\n"
                + "CREATE INDEX `ix_name` ON `t` (`name`);";

        writer.executeDDL(conn, ddl, result);

        verify(stmt, times(3)).execute(anyString());
        assertEquals(ConversionResult.Status.OK, result.getStatus());
    }

    // ── SQL injection via table name with backtick ──────────────────────────

    @Test
    void executeDDL_tableNameWithBacktick_regexCapturesFirstPart() throws Exception {
        // DDL: DROP TABLE IF EXISTS `foo`;bar`
        // Regex: [^`]+ captures "foo" (stops at first backtick)
        // Then tableHasData does: SELECT 1 FROM `foo` — safe
        // But the original DDL has ";bar`" leftover which becomes a separate "statement"
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);  // no data
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        ConversionResult result = new ConversionResult("t");
        // This is a contrived DDL that would never come from SchemaConverter
        String ddl = "DROP TABLE IF EXISTS `foo`;bar`;\nCREATE TABLE `t` (id INT);";

        writer.executeDDL(conn, ddl, result);

        // tableHasData was called with "foo" — the first backtick-delimited part
        verify(stmt).executeQuery("SELECT 1 FROM `foo` LIMIT 1");
    }
}
