package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;
import com.tool.common.model.ConversionResult;
import com.tool.common.model.IndexSchema;
import com.tool.common.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SchemaConverter — targets edge cases in DDL generation
 * that could produce invalid TiDB DDL or silently lose constraints.
 */
class SchemaConverterAdversarialTest {

    private SchemaConverter converter;
    private TableSchema table;
    private ConversionResult result;

    @BeforeEach
    void setUp() {
        converter = new SchemaConverter(new TypeMapper());
        table = new TableSchema();
        table.setSchemaName("dbo");
        table.setTableName("test_tbl");
        result = new ConversionResult("test_tbl");
    }

    // ── All columns skipped → null DDL + ERROR status ───────────────────────

    @Test
    void allColumnsSkipped_returnsNullWithError() {
        ColumnSchema col = new ColumnSchema();
        col.setName("rv");
        col.setSqlServerType("rowversion");
        col.setNullable(true);
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table with unconvertible columns should return null DDL");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().toLowerCase().contains("rowversion"));
    }

    @Test
    void allColumnsSkipped_withPrimaryKey_returnsNullWithError() {
        ColumnSchema col = new ColumnSchema();
        col.setName("rv");
        col.setSqlServerType("rowversion");
        col.setNullable(true);
        table.getColumns().add(col);
        table.getPrimaryKeyColumns().add("rv");

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table with unconvertible columns should return null even with PK");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
    }

    // ── Primary key on skipped column → null DDL ────────────────────────────

    @Test
    void primaryKeyOnSkippedColumn_returnsNullWithError() {
        ColumnSchema id = makeCol("id", "int", false);
        ColumnSchema rv = makeCol("rv", "rowversion", true);
        table.getColumns().add(id);
        table.getColumns().add(rv);
        table.getPrimaryKeyColumns().add("id");
        table.getPrimaryKeyColumns().add("rv");

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table with any unconvertible column should return null");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
    }

    // ── Unique constraint on skipped column → null DDL ──────────────────────

    @Test
    void uniqueConstraintOnSkippedColumn_returnsNullWithError() {
        ColumnSchema rv = makeCol("rv", "rowversion", true);
        table.getColumns().add(rv);
        table.getUniqueConstraints().put("uq_rv", "rv");

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table with any unconvertible column should return null");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
    }

    // ── Index on skipped column → null DDL ──────────────────────────────────

    @Test
    void indexOnSkippedColumn_returnsNullWithError() {
        ColumnSchema rv = makeCol("rv", "rowversion", true);
        table.getColumns().add(rv);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_rv");
        idx.setColumns(List.of("rv"));
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table with any unconvertible column should return null");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
    }

    // ── Comment with single quote (SQL injection in COMMENT) ────────────────

    @Test
    void commentWithSingleQuote_escapedProperly() {
        ColumnSchema col = makeCol("name", "varchar", true);
        col.setMaxLength(100);
        col.setComment("it's a \"name\" column");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        // Single quotes in COMMENT should be doubled
        assertTrue(ddl.contains("COMMENT 'it''s a \"name\" column'"),
                "Single quotes in COMMENT must be escaped to prevent DDL syntax error");
        assertFalse(ddl.contains("COMMENT 'it's"), "unescaped single quote breaks DDL");
    }

    @Test
    void commentWithBackslash_preservedAsIs() {
        ColumnSchema col = makeCol("path", "varchar", true);
        col.setMaxLength(200);
        col.setComment("C:\\Users\\admin");  // Java string = C:\Users\admin
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        // Backslashes are NOT escaped by SchemaConverter — passed through as-is
        assertTrue(ddl.contains("C:\\Users\\admin"),
                "Backslash in comment should be preserved as-is");
    }

    @Test
    void commentWithNewline_producesInvalidDDL() {
        ColumnSchema col = makeCol("desc", "varchar", true);
        col.setMaxLength(500);
        col.setComment("line1\nline2");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        // Newline inside COMMENT '...' breaks the DDL statement
        // This is a potential bug — SchemaConverter doesn't sanitize newlines
        assertTrue(ddl.contains("line1\nline2"),
                "Newline in COMMENT produces invalid DDL — unescaped newline breaks the statement");
    }

    // ── UUID() default dropped ───────────────────────────────────────────────

    @Test
    void uuidDefault_droppedWithWarning() {
        ColumnSchema col = makeCol("guid", "uniqueidentifier", false);
        col.setDefaultValue("NEWID()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "UUID() default should be dropped for VARCHAR(36) column");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("UUID()") && w.contains("dropped")));
    }

    // ── TIME column with function default → default dropped (plain TIME) ───

    @Test
    void plainTimeColumnWithGetdate_defaultDropped() {
        ColumnSchema col = makeCol("created", "time", true);
        // no scale → tidbType = "TIME" → matches equals("TIME")
        col.setDefaultValue("GETDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "CURRENT_TIMESTAMP default on TIME column should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("TIME") && w.contains("default") && w.contains("dropped")));
    }

    // ── BUG: TIME(n) with CURRENT_TIMESTAMP default not dropped ─────────────

    @Test
    void time3ColumnWithGetdate_defaultNotDropped_bug() {
        ColumnSchema col = makeCol("created", "time", true);
        col.setScale(3);  // tidbType = "TIME(3)" — doesn't match equals("TIME")
        col.setDefaultValue("GETDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        // BUG: tidbType.equals("TIME") doesn't match "TIME(3)" — CURRENT_TIMESTAMP
        // default survives, producing invalid TiDB DDL
        assertTrue(ddl.contains("  DEFAULT CURRENT_TIMESTAMP"),
                "BUG: TIME(3) column should drop CURRENT_TIMESTAMP but doesn't — "
                        + "equals(\"TIME\") doesn't match \"TIME(3)\"");
    }

    // ── Numeric default on DATETIME column → default dropped ─────────────────

    @Test
    void numericDefaultOnDatetime_defaultDropped() {
        ColumnSchema col = makeCol("dt", "datetime", true);
        col.setDefaultValue("0");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "Numeric default '0' on DATETIME column should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("numeric default")));
    }

    @Test
    void numericDefaultOnDate_defaultDropped() {
        ColumnSchema col = makeCol("d", "date", true);
        col.setDefaultValue("0");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("numeric default")));
    }

    // ── Empty string default on numeric column → default dropped ─────────────

    @Test
    void emptyStringDefaultOnInt_defaultDropped() {
        ColumnSchema col = makeCol("cnt", "int", true);
        col.setDefaultValue("''");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "Empty string default on INT column should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("empty string default") && w.contains("INT")));
    }

    @Test
    void emptyStringDefaultOnDatetime_defaultDropped() {
        ColumnSchema col = makeCol("dt", "datetime", true);
        col.setDefaultValue("''");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("empty string default") && w.contains("DATETIME")));
    }

    // ── BLOB/TEXT/JSON column with default → default dropped ─────────────────

    @Test
    void longtextWithDefault_defaultDropped() {
        ColumnSchema col = makeCol("xml_data", "xml", true);
        col.setDefaultValue("<root/>");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "LONGTEXT column cannot have a default in TiDB");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("LONGTEXT") && w.contains("default dropped")));
    }

    @Test
    void jsonWithDefault_defaultDropped() {
        ColumnSchema col = makeCol("j", "json", true);
        col.setDefaultValue("'{}'");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "JSON column cannot have a default in TiDB");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("JSON") && w.contains("default dropped")));
    }

    // ── Unknown function default → default dropped ───────────────────────────

    @Test
    void unknownFunctionDefault_dropped() {
        ColumnSchema col = makeCol("name", "varchar", true);
        col.setMaxLength(100);
        col.setDefaultValue("dbo.fn_something()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "Unknown function default should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("function not supported")));
    }

    @Test
    void lowerFunctionDefault_dropped() {
        ColumnSchema col = makeCol("name", "varchar", true);
        col.setMaxLength(100);
        col.setDefaultValue("lower(UPPER(name))");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "),
                "lower() function default should be dropped");
    }

    // ── Identity column suppresses default ───────────────────────────────────

    @Test
    void identityColumn_defaultSuppressed() {
        ColumnSchema col = makeCol("id", "int", false);
        col.setIdentity(true);
        col.setDefaultValue("0");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("AUTO_INCREMENT"));
        assertFalse(ddl.contains("  DEFAULT "),
                "Identity columns should not have a DEFAULT");
    }

    // ── UTC_TIMESTAMP fallback to CURRENT_TIMESTAMP ──────────────────────────

    @Test
    void utcTimestampDefault_fallsBackToCurrentTimestamp() {
        ColumnSchema col = makeCol("created", "datetime2", true);
        col.setScale(3);
        col.setDefaultValue("GETUTCDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP(3)"),
                "UTC_TIMESTAMP should fall back to CURRENT_TIMESTAMP with matching precision");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("UTC_TIMESTAMP") && w.contains("falling back")));
    }

    // ── CURRENT_TIMESTAMP precision matching ─────────────────────────────────

    @Test
    void currentTimestampOnDatetime2_6_precisionMatches() {
        ColumnSchema col = makeCol("ts", "datetime2", true);
        col.setScale(6);
        col.setDefaultValue("SYSDATETIME()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP(6)"),
                "DATETIME2(6) with SYSDATETIME should get CURRENT_TIMESTAMP(6)");
    }

    @Test
    void currentTimestampOnDatetime_noPrecision() {
        ColumnSchema col = makeCol("ts", "datetime", true);
        col.setDefaultValue("GETDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP"),
                "DATETIME with GETDATE should get plain CURRENT_TIMESTAMP");
        assertFalse(ddl.contains("CURRENT_TIMESTAMP("),
                "No precision specifier for plain DATETIME");
    }

    @Test
    void currentTimestampOnDate_convertedToCurdate() {
        ColumnSchema col = makeCol("d", "date", true);
        col.setDefaultValue("GETDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("DEFAULT CURDATE()"),
                "DATE column with GETDATE should use CURDATE()");
    }

    // ── Index key size exceeds 3072 bytes → index dropped ────────────────────

    @Test
    void indexKeySizeExceeds3072_indexDropped() {
        ColumnSchema col1 = makeCol("long_text", "nvarchar", true);
        col1.setMaxLength(2000); // 1000 chars × 4 bytes = 4000 bytes
        table.getColumns().add(col1);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_long");
        idx.setColumns(List.of("long_text"));
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("CREATE INDEX `ix_long`"),
                "Index exceeding 3072-byte key limit should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("3072-byte limit") && w.contains("dropped")));
    }

    // ── FULLTEXT and COLUMNSTORE indexes discarded ───────────────────────────

    @Test
    void fulltextIndex_discarded() {
        ColumnSchema col = makeCol("body", "nvarchar", true);
        col.setMaxLength(2000);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("ft_body");
        idx.setColumns(List.of("body"));
        idx.setFulltext(true);
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("CREATE INDEX `ft_body`"),
                "FULLTEXT index should be discarded");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("FULLTEXT") && w.contains("discarded")));
    }

    @Test
    void columnstoreIndex_discarded() {
        ColumnSchema col = makeCol("data", "int", true);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("cc_data");
        idx.setColumns(List.of("data"));
        idx.setColumnstore(true);
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("CREATE INDEX `cc_data`"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("COLUMNSTORE") && w.contains("discarded")));
    }

    // ── CLUSTERED index → converted to regular INDEX ─────────────────────────

    @Test
    void clusteredIndex_convertedToRegular() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("pk_clustered");
        idx.setColumns(List.of("id"));
        idx.setClustered(true);
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE INDEX `pk_clustered`"),
                "CLUSTERED index should be converted to regular INDEX");
        assertFalse(ddl.contains("CLUSTERED"), "CLUSTERED keyword should not appear in TiDB DDL");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("CLUSTERED") && w.contains("regular")));
    }

    // ── Filtered index → WHERE clause dropped ────────────────────────────────

    @Test
    void filteredIndex_whereClauseDropped() {
        ColumnSchema col = makeCol("status", "varchar", true);
        col.setMaxLength(20);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_active");
        idx.setColumns(List.of("status"));
        idx.setFilterDefinition("WHERE status <> 'deleted'");
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE INDEX `ix_active`"));
        assertFalse(ddl.contains("WHERE"),
                "Filtered index WHERE clause should be dropped");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("WHERE filter") && w.contains("dropped")));
    }

    // ── INCLUDE columns dropped from index ───────────────────────────────────

    @Test
    void indexWithIncludeColumns_includeDropped() {
        ColumnSchema col1 = makeCol("id", "int", false);
        ColumnSchema col2 = makeCol("name", "varchar", true);
        col2.setMaxLength(100);
        table.getColumns().add(col1);
        table.getColumns().add(col2);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_id");
        idx.setColumns(List.of("id"));
        idx.setIncludeColumns(List.of("name"));
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE INDEX `ix_id` ON `test_tbl` (`id`)"));
        // `name` appears in the column definition — check the INDEX line specifically
        String indexLine = ddl.lines()
                .filter(l -> l.contains("CREATE INDEX"))
                .findFirst().orElse("");
        assertFalse(indexLine.contains("`name`"),
                "INCLUDE column `name` should not appear in the CREATE INDEX statement");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("INCLUDE columns") && w.contains("dropped")));
    }

    // ── Partitioned table → warning ──────────────────────────────────────────

    @Test
    void partitionedTable_warnsAndProducesRegularTable() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);
        table.setPartitioned(true);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE TABLE `test_tbl`"));
        assertFalse(ddl.contains("PARTITION"),
                "Partitioned table should be converted to regular table");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("partitioned")));
    }

    // ── Foreign keys discarded ───────────────────────────────────────────────

    @Test
    void foreignKeys_warnedAndDiscarded() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);
        table.setForeignKeyCount(2);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("FOREIGN KEY"),
                "Foreign keys should be discarded from TiDB DDL");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("foreign key") && w.contains("discarded")));
    }

    // ── Check constraints discarded ──────────────────────────────────────────

    @Test
    void checkConstraints_discarded() {
        ColumnSchema col = makeCol("age", "int", true);
        table.getColumns().add(col);
        table.getCheckConstraints().add("age >= 0");

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("CHECK"),
                "CHECK constraints should be discarded");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("CHECK constraint") && w.contains("discarded")));
    }

    // ── Computed column → warning but still emitted ──────────────────────────

    @Test
    void computedColumn_warnsButEmitted() {
        ColumnSchema col = makeCol("total", "decimal", true);
        col.setPrecision(18);
        col.setScale(2);
        col.setComputed(true);
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("`total` DECIMAL(18,2)"),
                "Computed column should still be emitted as plain column");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("computed column")));
    }

    // ── DROP TABLE IF EXISTS ─────────────────────────────────────────────────

    @Test
    void dropIfExists_true_includesDropStatement() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, true);
        assertTrue(ddl.startsWith("DROP TABLE IF EXISTS `test_tbl`;"));
    }

    @Test
    void dropIfExists_false_noDropStatement() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("DROP TABLE"));
    }

    // ── TINYINT UNSIGNED byte estimation ─────────────────────────────────────

    @Test
    void tinyintUnsignedIndex_keySizeIs1Byte() {
        ColumnSchema col = makeCol("flag", "tinyint", true);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_flag");
        idx.setColumns(List.of("flag"));
        table.getIndexes().add(idx);

        // TINYINT UNSIGNED → 1 byte key, well under 3072
        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE INDEX `ix_flag`"),
                "TINYINT UNSIGNED index should be created (1 byte key)");
    }

    // ── DATETIME2(0) silent precision upgrade ────────────────────────────────

    @Test
    void datetime2_scale0_upgradedToDatetime6_defaultMatches() {
        ColumnSchema col = makeCol("ts", "datetime2", true);
        col.setScale(0);  // scale=0 → fsp=6 (falls to else branch) → DATETIME(6)
        col.setDefaultValue("GETDATE()");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP(6)"),
                "DATETIME2(0) silently upgrades to DATETIME(6), default should match precision");
    }

    // ── Negative numeric default on temporal column ──────────────────────────

    @Test
    void negativeNumericDefaultOnDatetime_defaultDropped() {
        ColumnSchema col = makeCol("dt", "datetime", true);
        col.setDefaultValue("-1");
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("  DEFAULT "));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("numeric default")));
    }

    // ── Multiple issues → table skipped due to unconvertible column ──────────

    @Test
    void multipleIssues_tableSkippedDueToUnconvertibleColumn() {
        ColumnSchema rv = makeCol("rv", "rowversion", true);
        ColumnSchema xml = makeCol("data", "xml", true);
        xml.setDefaultValue("'<?xml?>'");
        ColumnSchema id = makeCol("id", "int", false);
        table.getColumns().add(rv);
        table.getColumns().add(xml);
        table.getColumns().add(id);
        table.getPrimaryKeyColumns().add("rv");  // PK on skipped column!
        table.setForeignKeyCount(1);
        table.setPartitioned(true);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertNull(ddl, "Table should be skipped due to rowversion column");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().toLowerCase().contains("rowversion"));
    }

    // ── Empty table name (degenerate case) ───────────────────────────────────

    @Test
    void emptyTableName_producesDDLWithEmptyName() {
        table.setTableName("");
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("CREATE TABLE ``"),
                "Empty table name produces invalid DDL — should this be caught earlier?");
    }

    // ── Unique constraint with multiple columns ──────────────────────────────

    @Test
    void uniqueConstraint_multiColumn() {
        ColumnSchema col1 = makeCol("a", "int", false);
        ColumnSchema col2 = makeCol("b", "varchar", true);
        col2.setMaxLength(50);
        table.getColumns().add(col1);
        table.getColumns().add(col2);
        table.getUniqueConstraints().put("uq_ab", "a, b");

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertTrue(ddl.contains("UNIQUE KEY `uq_ab` (`a`, `b`)"));
    }

    // ── Index with null columns list → silently ignored ──────────────────────

    @Test
    void indexWithNullColumns_ignored() {
        ColumnSchema col = makeCol("id", "int", false);
        table.getColumns().add(col);

        IndexSchema idx = new IndexSchema();
        idx.setName("ix_null");
        idx.setColumns(null);
        table.getIndexes().add(idx);

        String ddl = converter.toCreateTableDDL(table, result, false);
        assertFalse(ddl.contains("CREATE INDEX"),
                "Index with null columns should be silently ignored");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ColumnSchema makeCol(String name, String sourceType, boolean nullable) {
        ColumnSchema col = new ColumnSchema();
        col.setName(name);
        col.setSqlServerType(sourceType);
        col.setNullable(nullable);
        return col;
    }
}
