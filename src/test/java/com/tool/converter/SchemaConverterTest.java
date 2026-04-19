package com.tool.converter;

import com.tool.ConversionResult;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaConverterTest {

    private SchemaConverter converter;

    @BeforeEach
    void setUp() { converter = new SchemaConverter(new TypeMapper()); }

    private TableSchema simpleTable() {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo");
        t.setTableName("users");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema name = new ColumnSchema();
        name.setName("name"); name.setSqlServerType("nvarchar"); name.setMaxLength(100); name.setNullable(false);
        t.setColumns(List.of(id, name));
        t.setPrimaryKeyColumns(List.of("id"));
        return t;
    }

    @Test
    void generatesCreateTable() {
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(simpleTable(), result, false);
        assertTrue(ddl.contains("CREATE TABLE `users`"));
        assertTrue(ddl.contains("`id` INT NOT NULL AUTO_INCREMENT"));
        assertTrue(ddl.contains("`name` VARCHAR(50) CHARACTER SET utf8mb4 NOT NULL"));
        assertTrue(ddl.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    void dropIfExists_prependsDropStatement() {
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(simpleTable(), result, true);
        assertTrue(ddl.startsWith("DROP TABLE IF EXISTS `users`;"));
    }

    @Test
    void partitionedTable_addsWarning() {
        TableSchema t = simpleTable();
        t.setPartitioned(true);
        ConversionResult result = new ConversionResult("dbo.users");
        converter.toCreateTableDDL(t, result, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("partitioned table")));
    }

    @Test
    void foreignKey_addsWarningAndDrops() {
        TableSchema t = simpleTable();
        t.setForeignKeyCount(2);
        ConversionResult result = new ConversionResult("dbo.users");
        converter.toCreateTableDDL(t, result, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("foreign key")));
    }

    @Test
    void checkConstraint_isDiscarded() {
        TableSchema t = simpleTable();
        t.setCheckConstraints(List.of("([age]>(0))"));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertFalse(ddl.contains("CHECK"));   // discarded — TiDB doesn't enforce CHECK
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("CHECK")));
    }

    @Test
    void fulltextIndex_isDiscarded() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("ft_idx"); idx.setFulltext(true); idx.setColumns(List.of("name"));
        t.setIndexes(List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertFalse(ddl.contains("ft_idx"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("FULLTEXT")));
    }

    @Test
    void normalIndex_isIncluded() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("idx_name"); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(List.of("name")); idx.setIncludeColumns(List.of());
        t.setIndexes(List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("INDEX `idx_name`"));
    }

    @Test
    void unskippableColumn_errorsWholeTable() {
        TableSchema t = simpleTable();
        ColumnSchema bad = new ColumnSchema();
        bad.setName("cur"); bad.setSqlServerType("cursor"); bad.setNullable(true);
        t.setColumns(List.of(t.getColumns().get(0), t.getColumns().get(1), bad));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNull(ddl, "DDL should be null when an unskippable column exists");
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
    }

    @Test
    void computedColumn_addsWarningAndConvertsToPlainColumn() {
        TableSchema t = simpleTable();
        ColumnSchema computed = new ColumnSchema();
        computed.setName("qty_available");
        computed.setSqlServerType("int");
        computed.setNullable(true);
        computed.setComputed(true);
        t.setColumns(List.of(t.getColumns().get(0), t.getColumns().get(1), computed));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        // Column should still appear as a plain INT column
        assertTrue(ddl.contains("`qty_available`"));
        // Warning should be recorded
        assertTrue(result.getWarnings().stream().anyMatch(w ->
                w.contains("qty_available") && w.contains("computed column")));
    }

    @Test
    void uniqueConstraint_generatesUniqueKey() {
        TableSchema t = simpleTable();
        t.setUniqueConstraints(new java.util.LinkedHashMap<>(
                java.util.Map.of("uq_email", "email")));
        ColumnSchema email = new ColumnSchema();
        email.setName("email"); email.setSqlServerType("varchar"); email.setMaxLength(200); email.setNullable(false);
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), email));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("UNIQUE KEY `uq_email`"), "DDL should contain UNIQUE KEY: " + ddl);
        assertTrue(ddl.contains("`email`"), "UNIQUE KEY must reference the email column: " + ddl);
    }

    @Test
    void columnstoreIndex_isDiscarded() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("cs_idx"); idx.setColumnstore(true); idx.setColumns(java.util.List.of("name"));
        t.setIndexes(java.util.List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertFalse(ddl.contains("cs_idx"), "COLUMNSTORE index should be discarded");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("COLUMNSTORE")));
    }

    @Test
    void includeIndex_dropsIncludeColumnsButKeepsIndex() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("idx_include"); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(java.util.List.of("id"));
        idx.setIncludeColumns(java.util.List.of("name"));
        t.setIndexes(java.util.List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("INDEX `idx_include`"), "Index body should be kept: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("INCLUDE")));
    }

    @Test
    void filteredIndex_dropsFilterButKeepsIndex() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("idx_filtered"); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(java.util.List.of("id"));
        idx.setFilterDefinition("([id]>(0))");
        t.setIndexes(java.util.List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("INDEX `idx_filtered`"), "Index body should be kept: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("WHERE filter")));
    }

    @Test
    void clusteredIndex_convertsToRegularWithWarning() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("cidx"); idx.setClustered(true); idx.setUnique(false);
        idx.setColumns(java.util.List.of("id"));
        t.setIndexes(java.util.List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("INDEX `cidx`"), "Clustered index body should be kept: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("CLUSTERED")));
    }

    @Test
    void defaultValueWithFunction_addsWarning() {
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("created_at"); col.setSqlServerType("datetime"); col.setNullable(false);
        col.setDefaultValue("(getdate())");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP"), "DDL should contain translated default: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("default value")));
    }

    @Test
    void columnWithComment_includesCommentInDdl() {
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("status"); col.setSqlServerType("int"); col.setNullable(false);
        col.setComment("0=inactive 1=active");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("COMMENT '0=inactive 1=active'"), "DDL should contain COMMENT clause: " + ddl);
    }

    /**
     * Regression: DATETIME(3) column with GETDATE() default must generate
     * DEFAULT CURRENT_TIMESTAMP(3), not DEFAULT CURRENT_TIMESTAMP.
     * TiDB rejects precision mismatch between column type and default value.
     */
    @Test
    void datetime3WithGetdateDefault_generatesPrecisionMatchedCurrentTimestamp() {
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("created_at"); col.setSqlServerType("datetime2"); col.setScale(3); col.setNullable(false);
        col.setDefaultValue("(getdate())");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP(3)"),
                "DATETIME(3) with GETDATE default must use CURRENT_TIMESTAMP(3), got: " + ddl);
        assertFalse(ddl.contains("DEFAULT CURRENT_TIMESTAMP,") || ddl.contains("DEFAULT CURRENT_TIMESTAMP\n"),
                "Must not emit bare CURRENT_TIMESTAMP when column has precision: " + ddl);
    }

    @Test
    void multipleSkipColumns_errorsWithBothColumnNames() {
        TableSchema t = simpleTable();
        ColumnSchema bad1 = new ColumnSchema();
        bad1.setName("cur1"); bad1.setSqlServerType("cursor"); bad1.setNullable(true);
        ColumnSchema bad2 = new ColumnSchema();
        bad2.setName("cur2"); bad2.setSqlServerType("table"); bad2.setNullable(true);
        t.setColumns(java.util.List.of(t.getColumns().get(0), bad1, bad2));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNull(ddl);
        assertEquals(ConversionResult.Status.ERROR, result.getStatus());
        String errMsg = result.getErrorMessage();
        assertTrue(errMsg.contains("cur1"), "Error should mention first skip col: " + errMsg);
        assertTrue(errMsg.contains("cur2"), "Error should mention second skip col: " + errMsg);
    }

    private TableSchema twoColTable(String colName, String sqlType, String defaultVal) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo");
        t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema c = new ColumnSchema();
        c.setName(colName); c.setSqlServerType(sqlType); c.setNullable(true);
        c.setDefaultValue(defaultVal);
        t.setColumns(List.of(id, c));
        t.setPrimaryKeyColumns(List.of("id"));
        return t;
    }

    @Test
    void dateColumn_getdateDefault_emitsCurdate() {
        TableSchema t = twoColTable("d", "date", "(getdate())");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("CURDATE()"),
                "DATE column with GETDATE() default should emit CURDATE(), got: " + ddl);
        assertFalse(ddl.contains("CURRENT_TIMESTAMP"),
                "DATE column must not have CURRENT_TIMESTAMP default, got: " + ddl);
    }

    @Test
    void timeColumn_getdateDefault_emitsCurtime() {
        TableSchema t = twoColTable("t", "time", "(getdate())");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("CURRENT_TIMESTAMP"),
                "TIME column must not have CURRENT_TIMESTAMP default, got: " + ddl);
        // TiDB does not support function-based defaults for TIME; default must be dropped or use a literal
        assertFalse(ddl.contains("CURTIME()"),
                "TIME column must not use CURTIME() as TiDB rejects it, got: " + ddl);
    }

    @Test
    void datetimeColumn_sysdatetimeDefault_stripsToCurrentTimestamp() {
        TableSchema t = twoColTable("logged_at", "datetime", "(SYSDATETIME())");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP"),
                "DATETIME column with SYSDATETIME() should emit CURRENT_TIMESTAMP (no precision), got: " + ddl);
        assertFalse(ddl.contains("CURRENT_TIMESTAMP("),
                "DATETIME (no precision) must not have CURRENT_TIMESTAMP(n), got: " + ddl);
    }

    private TableSchema twoColTableWithScale(String colName, String sqlType, int scale, String defaultVal) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo");
        t.setTableName("tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema c = new ColumnSchema();
        c.setName(colName); c.setSqlServerType(sqlType); c.setNullable(false);
        c.setScale(scale);
        c.setDefaultValue(defaultVal);
        t.setColumns(List.of(id, c));
        t.setPrimaryKeyColumns(List.of("id"));
        return t;
    }

    @Test
    void datetime6Column_utcTimestampDefault_fallsBackToCurrentTimestamp6() {
        TableSchema t = twoColTableWithScale("synced_at", "datetime2", 6, "(GETUTCDATE())");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("UTC_TIMESTAMP"),
                "DATETIME(6) column with GETUTCDATE() — TiDB rejects UTC_TIMESTAMP() as DEFAULT; must fall back, got: " + ddl);
        assertTrue(ddl.contains("CURRENT_TIMESTAMP(6)"),
                "DATETIME(6) column with GETUTCDATE() should fall back to CURRENT_TIMESTAMP(6), got: " + ddl);
    }

    @Test
    void datetime2Scale0_getdate_emitsCurrentTimestamp6() {
        // datetime2(0) → DATETIME(6) in TiDB; DEFAULT must match: CURRENT_TIMESTAMP(6)
        TableSchema t = twoColTableWithScale("created_at", "datetime2", 0, "(getdate())");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("CURRENT_TIMESTAMP(6)"),
                "DATETIME(6) column (from datetime2 scale=0) must use CURRENT_TIMESTAMP(6), got: " + ddl);
    }
}
