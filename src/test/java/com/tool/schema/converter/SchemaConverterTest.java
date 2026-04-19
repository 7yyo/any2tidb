package com.tool.schema.converter;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.ColumnSchema;
import com.tool.common.model.IndexSchema;
import com.tool.common.model.TableSchema;
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
    void defaultValueWithFunction_getdate_noGenericWarn() {
        // Bug 3 fix: GETDATE() → CURRENT_TIMESTAMP is a known-safe mapping; no generic warning
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("created_at"); col.setSqlServerType("datetime"); col.setNullable(false);
        col.setDefaultValue("(getdate())");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("DEFAULT CURRENT_TIMESTAMP"), "DDL should contain translated default: " + ddl);
        // No generic "verify semantics" warning for well-known mapping
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("verify semantics")),
                "GETDATE() is a known-safe mapping and must not produce a generic 'verify semantics' warning");
    }

    @Test
    void defaultValueWithUnknownFunction_addsWarning() {
        // Unknown function-based defaults should be dropped with a warning
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("score"); col.setSqlServerType("int"); col.setNullable(false);
        col.setDefaultValue("(dbo.fn_default_score())");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        // Default must be dropped (not passed through to TiDB)
        assertFalse(ddl.contains("fn_default_score"),
                "Unknown function default must be dropped from DDL");
        // A warning must be recorded
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("score") && w.contains("default dropped")),
                "Unknown function default must produce 'default dropped' warning");
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
    void timeColumn_getdateDefault_dropsDefault() {
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

    @Test
    void longtextColumn_defaultDropped() {
        // BLOB/TEXT/JSON columns cannot have defaults in TiDB — default must be silently dropped with a warning
        // Use a non-empty literal default so it survives mapDefaultValue without being nulled out
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("contractSummary"); col.setSqlServerType("nvarchar"); col.setMaxLength(-1); col.setNullable(true);
        col.setDefaultValue("('N/A')");   // maps to: 'N/A' (quoted literal, non-empty)
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        // ENGINE=InnoDB DEFAULT CHARSET=... contains "DEFAULT" — check column-level default specifically
        assertFalse(ddl.contains("`contractSummary` LONGTEXT DEFAULT"), "LONGTEXT column must not have a DEFAULT clause: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("contractSummary") && w.contains("default dropped")),
                "Must warn that LONGTEXT default was dropped: " + result.getWarnings());
    }

    @Test
    void jsonColumn_defaultDropped() {
        TableSchema t = simpleTable();
        ColumnSchema col = new ColumnSchema();
        col.setName("meta"); col.setSqlServerType("json"); col.setNullable(true);
        col.setDefaultValue("('{}')");
        t.setColumns(java.util.List.of(t.getColumns().get(0), t.getColumns().get(1), col));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("`meta` JSON DEFAULT"), "JSON column must not have a DEFAULT clause: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("meta") && w.contains("default dropped")));
    }

    @Test
    void numericColumn_emptyStringDefault_dropsDefault() {
        // SQL Server allows DEFAULT ('') on int/numeric (coerces to 0), TiDB rejects it with [1067]
        TableSchema t = twoColTable("actionOrder", "numeric", "('')");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("`actionOrder` DECIMAL DEFAULT"),
                "Numeric column with empty-string default must not emit DEFAULT clause: " + ddl);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("actionOrder") && w.contains("default dropped")),
                "Must warn that empty-string default was dropped: " + r.getWarnings());
    }

    @Test
    void intColumn_emptyStringDefault_dropsDefault() {
        TableSchema t = twoColTable("templateVersion", "int", "('')");
        ConversionResult r = new ConversionResult("dbo.tbl");
        String ddl = converter.toCreateTableDDL(t, r, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("`templateVersion` INT DEFAULT"),
                "INT column with empty-string default must not emit DEFAULT clause: " + ddl);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("templateVersion") && w.contains("default dropped")),
                "Must warn that empty-string default was dropped: " + r.getWarnings());
    }

    // ── Type × Default matrix tests ──────────────────────────────────────────

    @Test
    void numericTypeMatrix_emptyStringDefault_allDropped() {
        // All numeric SS types must drop empty-string default with a warning
        String[][] cases = {
            {"intCol",      "int",     "('')"},
            {"bigintCol",   "bigint",  "('')"},
            {"smallintCol", "smallint","('')"},
            {"tinyintCol",  "tinyint", "('')"},
            {"decimalCol",  "decimal", "('')"},
            {"numericCol",  "numeric", "('')"},
            {"floatCol",    "float",   "('')"},
            {"realCol",     "real",    "('')"},
        };
        for (String[] c : cases) {
            TableSchema t = twoColTable(c[0], c[1], c[2]);
            ConversionResult r = new ConversionResult("dbo.tbl");
            String ddl = converter.toCreateTableDDL(t, r, false);
            assertNotNull(ddl, "DDL must not be null for " + c[1]);
            assertFalse(ddl.contains("`" + c[0] + "` " + c[1].toUpperCase() + " DEFAULT"),
                    c[1] + " empty-string default must be dropped, got: " + ddl);
            assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains(c[0]) && w.contains("default dropped")),
                    c[1] + " must warn 'default dropped': " + r.getWarnings());
        }
    }

    @Test
    void temporalTypeMatrix_emptyStringDefault_allDropped() {
        // All temporal SS types must drop empty-string default with a warning
        String[][] cases = {
            {"d",  "date",      "('')"},
            {"t",  "time",      "('')"},
            {"dt", "datetime",  "('')"},
            {"dt2","datetime2", "('')"},
        };
        for (String[] c : cases) {
            TableSchema t = twoColTable(c[0], c[1], c[2]);
            ConversionResult r = new ConversionResult("dbo.tbl");
            String ddl = converter.toCreateTableDDL(t, r, false);
            assertNotNull(ddl, "DDL must not be null for " + c[1]);
            // Column must not have any DEFAULT clause
            assertFalse(ddl.contains("`" + c[0] + "` ") && ddl.contains("DEFAULT ''"),
                    c[1] + " empty-string default must be dropped, got: " + ddl);
            assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains(c[0]) && w.contains("default dropped")),
                    c[1] + " must warn 'default dropped': " + r.getWarnings());
        }
    }

    @Test
    void numericTypeMatrix_zeroLiteralDefault_retained() {
        // Numeric columns with a literal 0 default must retain DEFAULT 0
        String[][] cases = {
            {"intCol",    "int",     "((0))"},
            {"bigintCol", "bigint",  "((0))"},
            {"decimalCol","decimal", "((0))"},
            {"floatCol",  "float",   "((0))"},
        };
        for (String[] c : cases) {
            TableSchema t = twoColTable(c[0], c[1], c[2]);
            ConversionResult r = new ConversionResult("dbo.tbl");
            String ddl = converter.toCreateTableDDL(t, r, false);
            assertNotNull(ddl, "DDL must not be null for " + c[1]);
            assertTrue(ddl.contains("DEFAULT 0"),
                    c[1] + " with literal 0 default must retain DEFAULT 0, got: " + ddl);
            assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains(c[0]) && w.contains("default dropped")),
                    c[1] + " literal 0 must NOT produce 'default dropped' warning: " + r.getWarnings());
        }
    }

    @Test
    void stringTypeMatrix_emptyStringDefault_retained() {
        // String (varchar/nvarchar/char) columns may have DEFAULT '' — TiDB accepts it
        // Note: maxLength must be set so varchar/nvarchar don't map to LONGTEXT
        // (LONGTEXT columns have their defaults dropped separately).
        String[][] cases = {
            {"vcol",  "varchar",  "('')"},
            {"nvcol", "nvarchar", "('')"},
            {"ccol",  "char",     "('')"},
        };
        for (String[] c : cases) {
            TableSchema t = new TableSchema();
            t.setSchemaName("dbo"); t.setTableName("tbl");
            ColumnSchema id = new ColumnSchema();
            id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
            ColumnSchema col = new ColumnSchema();
            col.setName(c[0]); col.setSqlServerType(c[1]); col.setMaxLength(50); col.setNullable(true);
            col.setDefaultValue(c[2]);
            t.setColumns(java.util.List.of(id, col));
            t.setPrimaryKeyColumns(java.util.List.of("id"));
            ConversionResult r = new ConversionResult("dbo.tbl");
            String ddl = converter.toCreateTableDDL(t, r, false);
            assertNotNull(ddl, "DDL must not be null for " + c[1]);
            assertTrue(ddl.contains("DEFAULT ''"),
                    c[1] + " empty-string default must be retained as DEFAULT '', got: " + ddl);
            assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains(c[0]) && w.contains("default dropped")),
                    c[1] + " empty-string default must NOT produce 'default dropped' warning: " + r.getWarnings());
        }
    }

    // ── Index key-length guard (Error 1071) tests ─────────────────────────────

    /** Build a table whose index columns are all nvarchar with the given maxLength. */
    private TableSchema wideIndexTable(String idxName, int numCols, int nvarcharLen) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("wide_tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        java.util.List<ColumnSchema> cols = new java.util.ArrayList<>();
        cols.add(id);
        java.util.List<String> idxCols = new java.util.ArrayList<>();
        for (int i = 1; i <= numCols; i++) {
            ColumnSchema c = new ColumnSchema();
            c.setName("c" + i); c.setSqlServerType("nvarchar"); c.setMaxLength(nvarcharLen); c.setNullable(true);
            cols.add(c);
            idxCols.add("c" + i);
        }
        t.setColumns(cols);
        t.setPrimaryKeyColumns(java.util.List.of("id"));
        IndexSchema idx = new IndexSchema();
        idx.setName(idxName); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(idxCols); idx.setIncludeColumns(java.util.List.of());
        t.setIndexes(java.util.List.of(idx));
        return t;
    }

    @Test
    void indexKeyTooLong_isDropped_withWarning() {
        // 3 × nvarchar(800 bytes) → charLen=400 → 400×4=1600 bytes each → 4800 bytes > 3072 → drop + warn
        TableSchema t = wideIndexTable("idx_wide", 3, 800);
        ConversionResult result = new ConversionResult("dbo.wide_tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("idx_wide"), "Over-limit index must be dropped from DDL: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("idx_wide") && w.contains("1071")),
                "Must warn about Error 1071: " + result.getWarnings());
    }

    @Test
    void indexKeyUnderLimit_isRetained() {
        // 2 × nvarchar(200 bytes) → charLen=100 → 100×4=400 bytes each → 800 bytes ≤ 3072 → keep index
        TableSchema t = wideIndexTable("idx_ok", 2, 200);
        ConversionResult result = new ConversionResult("dbo.wide_tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("idx_ok"), "Within-limit index must be retained: " + ddl);
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("1071")),
                "Must not warn about Error 1071 for short index: " + result.getWarnings());
    }

    @Test
    void indexKeyExactly3072_isRetained() {
        // 3 × nvarchar(512 bytes) → charLen=256 → 256×4=1024 bytes each → 3072 bytes exactly at limit → keep
        TableSchema t = wideIndexTable("idx_exact", 3, 512);
        ConversionResult result = new ConversionResult("dbo.wide_tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("idx_exact"), "Index at exactly 3072 bytes must be retained: " + ddl);
    }

    @Test
    void indexOnIntColumns_isRetained_noBytesWarning() {
        // 4 × int → 4 × 4 = 16 bytes, well under limit
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName("int_tbl");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema a = new ColumnSchema(); a.setName("a"); a.setSqlServerType("int"); a.setNullable(true);
        ColumnSchema b = new ColumnSchema(); b.setName("b"); b.setSqlServerType("int"); b.setNullable(true);
        t.setColumns(java.util.List.of(id, a, b));
        t.setPrimaryKeyColumns(java.util.List.of("id"));
        IndexSchema idx = new IndexSchema();
        idx.setName("idx_int"); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(java.util.List.of("a", "b")); idx.setIncludeColumns(java.util.List.of());
        t.setIndexes(java.util.List.of(idx));
        ConversionResult result = new ConversionResult("dbo.int_tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertTrue(ddl.contains("idx_int"), "INT index must be retained: " + ddl);
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("1071")));
    }

    @Test
    void compositeIndex_oneColOverLimit_isDropped() {
        // 1 × nvarchar(1600 bytes) → charLen=800 → 800×4=3200 bytes > 3072 → drop
        TableSchema t = wideIndexTable("idx_onewide", 1, 1600);
        ConversionResult result = new ConversionResult("dbo.wide_tbl");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertNotNull(ddl);
        assertFalse(ddl.contains("idx_onewide"), "Single-column over-limit index must be dropped: " + ddl);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("idx_onewide") && w.contains("1071")));
    }
}
