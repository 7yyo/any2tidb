package com.tool.integration;

import com.tool.ConversionResult;
import com.tool.converter.SchemaConverter;
import com.tool.converter.TypeMapper;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import com.tool.writer.TiDBWriter;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes converter-generated DDL against a live TiDB instance and verifies
 * the table is actually created. Covers every branch in SchemaConverter/TypeMapper
 * that is unit-tested in SchemaConverterTest / TypeMapperTest.
 *
 * Requires TiDB on 127.0.0.1:4000 (root / no password).
 * Run with: mvn test -Pintegration -Dtest=DdlExecutionTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DdlExecutionTest {

    private static final String TIDB_URL =
            "jdbc:mysql://127.0.0.1:4000/testdb?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    private static Connection tidbConn;
    private final SchemaConverter converter = new SchemaConverter(new TypeMapper());
    private final TiDBWriter writer = new TiDBWriter();

    @BeforeAll
    static void connect() throws Exception {
        tidbConn = DriverManager.getConnection(TIDB_URL, "root", "");
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (tidbConn != null) tidbConn.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void drop(String name) {
        try (Statement st = tidbConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + name + "`");
        } catch (SQLException ignored) {}
    }

    private boolean tableExists(String name) throws SQLException {
        try (ResultSet rs = tidbConn.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private ColumnSchema col(String name, String type, boolean nullable) {
        ColumnSchema c = new ColumnSchema();
        c.setName(name); c.setSqlServerType(type); c.setNullable(nullable);
        return c;
    }

    private ColumnSchema colLen(String name, String type, int maxLength, boolean nullable) {
        ColumnSchema c = col(name, type, nullable);
        c.setMaxLength(maxLength);
        return c;
    }

    private ColumnSchema colPrec(String name, String type, int precision, int scale, boolean nullable) {
        ColumnSchema c = col(name, type, nullable);
        c.setPrecision(precision); c.setScale(scale);
        return c;
    }

    private ColumnSchema colDefault(String name, String type, boolean nullable, String defaultValue) {
        ColumnSchema c = col(name, type, nullable);
        c.setDefaultValue(defaultValue);
        return c;
    }

    private ColumnSchema colDefaultScale(String name, String type, int scale, boolean nullable, String defaultValue) {
        ColumnSchema c = col(name, type, nullable);
        c.setScale(scale);
        c.setDefaultValue(defaultValue);
        return c;
    }

    private ColumnSchema colDefaultLen(String name, String type, int maxLength, boolean nullable, String defaultValue) {
        ColumnSchema c = colLen(name, type, maxLength, nullable);
        c.setDefaultValue(defaultValue);
        return c;
    }

    private TableSchema table(String tableName, List<ColumnSchema> cols, List<String> pk) {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo"); t.setTableName(tableName);
        t.setColumns(cols); t.setPrimaryKeyColumns(pk);
        return t;
    }

    /** Generate DDL, execute on TiDB, return ConversionResult. */
    private ConversionResult exec(TableSchema t, boolean drop) throws SQLException {
        ConversionResult result = new ConversionResult("dbo." + t.getTableName());
        String ddl = converter.toCreateTableDDL(t, result, drop);
        writer.executeDDL(tidbConn, ddl, result);
        return result;
    }

    // ── test cases ───────────────────────────────────────────────────────────

    /**
     * DE01: Integer family — int, bigint, smallint, tinyint, bit
     */
    @Test @Order(1)
    void de01_integerTypes() throws Exception {
        String tn = "de_integers";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",       "int",      false),
                col("big_col",  "bigint",   true),
                col("small_col","smallint", true),
                col("tiny_col", "tinyint",  true),
                col("flag",     "bit",      true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE02: Numeric/decimal with explicit precision+scale
     */
    @Test @Order(2)
    void de02_decimal() throws Exception {
        String tn = "de_decimal";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",    "int",     false),
                colPrec("price",  "decimal", 10, 2, true),
                colPrec("amount", "numeric", 18, 4, true),
                col("money_col",  "money",   true),
                col("small_money","smallmoney", true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        // money/smallmoney should warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("MONEY")));
        drop(tn);
    }

    /**
     * DE03: Float / real
     */
    @Test @Order(3)
    void de03_floatingPoint() throws Exception {
        String tn = "de_float";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",  "int",  false),
                col("d",   "float", true),
                col("f",   "real",  true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE04: Date/time types — date, time, datetime, datetime2(3), datetime2(7), datetimeoffset
     */
    @Test @Order(4)
    void de04_dateTimeTypes() throws Exception {
        String tn = "de_datetime";
        drop(tn);
        ColumnSchema dt2_3  = colPrec("dt2_3",   "datetime2", 0, 3, true);
        ColumnSchema dt2_7  = colPrec("dt2_7",   "datetime2", 0, 7, true);
        ColumnSchema dto    = col("dto", "datetimeoffset", true);
        TableSchema t = table(tn, List.of(
                col("id",  "int",      false),
                col("d",   "date",     true),
                col("ti",  "time",     true),
                col("dt",  "datetime", true),
                dt2_3, dt2_7, dto
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        // datetime2(7) truncated to 6 → warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("truncated")));
        // datetimeoffset → warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("DATETIMEOFFSET")));
        drop(tn);
    }

    /**
     * DE05: String types — char, varchar, nchar, nvarchar, text, ntext
     */
    @Test @Order(5)
    void de05_stringTypes() throws Exception {
        String tn = "de_strings";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",       "int",     false),
                colLen("c1",    "char",    10,   true),
                colLen("c2",    "varchar", 100,  true),
                colLen("c3",    "varchar", -1,   true),   // VARCHAR(MAX) → LONGTEXT
                colLen("c4",    "nchar",   20,   true),   // nchar(20) → bytes=20, chars=10
                colLen("c5",    "nvarchar", 200, true),   // nvarchar(100) → chars=100
                colLen("c6",    "nvarchar", -1,  true),   // NVARCHAR(MAX) → LONGTEXT
                col("c7",       "text",    true),
                col("c8",       "ntext",   true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("MAX") || w.contains("LONGTEXT")));
        drop(tn);
    }

    /**
     * DE06: Binary types — binary, varbinary, varbinary(max), image
     */
    @Test @Order(6)
    void de06_binaryTypes() throws Exception {
        String tn = "de_binary";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",    "int",        false),
                colLen("b1", "binary",     16,  true),
                colLen("b2", "varbinary",  256, true),
                colLen("b3", "varbinary",  -1,  true),   // VARBINARY(MAX) → LONGBLOB
                col("b4",    "image",      true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("LONGBLOB") && w.contains("VARBINARY")),
                "Expected VARBINARY(MAX) → LONGBLOB warning, got: " + r.getWarnings());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("IMAGE")),
                "Expected IMAGE → LONGBLOB warning, got: " + r.getWarnings());
        drop(tn);
    }

    /**
     * DE07: Special types — uniqueidentifier, xml, json
     * (GEOMETRY requires TiDB v8.3+; tested environment is v7.x, so geometry is skipped here)
     */
    @Test @Order(7)
    void de07_specialTypes() throws Exception {
        String tn = "de_special";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",   "int",              false),
                col("guid", "uniqueidentifier", true),
                col("meta", "xml",              true),
                col("data", "json",             true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE08: AUTO_INCREMENT identity column
     */
    @Test @Order(8)
    void de08_identityColumn() throws Exception {
        String tn = "de_identity";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        id.setIdentity(true);
        ColumnSchema val = colLen("val", "varchar", 50, true);
        TableSchema t = table(tn, List.of(id, val), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE09: Indexes — regular, unique, clustered (→ regular), INCLUDE (→ warn, drop INCLUDE),
     *        fulltext (discarded), computed column (→ plain + warn)
     */
    @Test @Order(9)
    void de09_indexVariants() throws Exception {
        String tn = "de_indexes";
        drop(tn);

        ColumnSchema id   = col("id",   "int",     false);
        ColumnSchema code = colLen("code", "varchar", 50, true);
        ColumnSchema name = colLen("name", "varchar", 100, true);
        ColumnSchema descr= colLen("descr","varchar", 200, true);
        ColumnSchema comp = col("computed_col", "int", true);
        comp.setComputed(true);

        IndexSchema regular   = new IndexSchema();
        regular.setName("IX_code"); regular.setColumns(List.of("code")); regular.setIncludeColumns(List.of());
        IndexSchema unique    = new IndexSchema();
        unique.setName("IX_name"); unique.setUnique(true); unique.setColumns(List.of("name")); unique.setIncludeColumns(List.of());
        IndexSchema clustered = new IndexSchema();
        clustered.setName("IX_clustered"); clustered.setClustered(true); clustered.setColumns(List.of("code")); clustered.setIncludeColumns(List.of());
        IndexSchema withInclude = new IndexSchema();
        withInclude.setName("IX_include"); withInclude.setColumns(List.of("code")); withInclude.setIncludeColumns(List.of("name", "descr"));
        IndexSchema fulltext  = new IndexSchema();
        fulltext.setName("FT_name"); fulltext.setFulltext(true); fulltext.setColumns(List.of("name")); fulltext.setIncludeColumns(List.of());

        TableSchema t = table(tn, List.of(id, code, name, descr, comp), List.of("id"));
        t.setIndexes(List.of(regular, unique, clustered, withInclude, fulltext));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        // INCLUDE dropped → warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("INCLUDE")));
        // FULLTEXT discarded → warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("FULLTEXT")));
        // computed column → warn
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("computed column")));
        drop(tn);
    }

    /**
     * DE10: dropIfExists=true — table can be recreated without error
     */
    @Test @Order(10)
    void de10_dropIfExists() throws Exception {
        String tn = "de_drop";
        drop(tn);
        TableSchema t = table(tn, List.of(col("id", "int", false)), List.of("id"));
        exec(t, false);
        assertTrue(tableExists(tn));
        // Second run with drop
        ConversionResult r2 = exec(t, true);
        assertNotEquals(ConversionResult.Status.ERROR, r2.getStatus(), r2.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE11: CHECK constraint — discarded with warning, DDL still valid
     */
    @Test @Order(11)
    void de11_checkConstraintDiscarded() throws Exception {
        String tn = "de_check";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",  "int", false),
                col("age", "int", true)
        ), List.of("id"));
        t.setCheckConstraints(List.of("([age]>(0))"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("CHECK")));
        drop(tn);
    }

    /**
     * DE12: smalldatetime → DATETIME; datetime2 with scale=0 → DATETIME(6)
     */
    @Test @Order(12)
    void de12_smalldatetimeAndDatetime2Scale0() throws Exception {
        String tn = "de_smalldt";
        drop(tn);
        ColumnSchema dt2zero = new ColumnSchema();
        dt2zero.setName("dt2_zero"); dt2zero.setSqlServerType("datetime2");
        dt2zero.setPrecision(27); dt2zero.setScale(0); dt2zero.setNullable(true);

        TableSchema t = table(tn, List.of(
                col("id",    "int",           false),
                col("sdt",   "smalldatetime", true),
                dt2zero
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertFalse(r.getWarnings().stream().anyMatch(w -> w.toLowerCase().contains("smalldatetime")),
                "smalldatetime should not produce a warning");
        drop(tn);
    }

    /**
     * DE13: nchar / char / sysname → TiDB DDL executes without error
     */
    @Test @Order(13)
    void de13_ncharAndSysname() throws Exception {
        String tn = "de_nchar";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",    "int",     false),
                colLen("nc", "nchar",   20,  true),   // 20 bytes → CHAR(10) CHARACTER SET utf8mb4
                colLen("ch", "char",    10,  true),   // CHAR(10)
                col("sn",    "sysname", true)          // VARCHAR(128)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE14: rowversion/timestamp → BIGINT UNSIGNED; hierarchyid → VARCHAR(4000);
     *       sql_variant → LONGTEXT; all with warnings
     */
    @Test @Order(14)
    void de14_rowversionHierarchyidSqlVariant() throws Exception {
        String tn = "de_rowver";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",   "int",         false),
                col("rv",   "rowversion",  true),
                col("ts",   "timestamp",   true),
                col("hier", "hierarchyid", true),
                col("sv",   "sql_variant", true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("ROWVERSION")),
                "Expected rowversion/timestamp warning");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("HIERARCHYID")),
                "Expected hierarchyid warning");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("SQL_VARIANT")),
                "Expected sql_variant warning");
        drop(tn);
    }

    /**
     * DE15: "integer" alias for INT; numeric with precision; ntext → LONGTEXT
     */
    @Test @Order(15)
    void de15_integerAliasNumericNtext() throws Exception {
        String tn = "de_integer_alias";
        drop(tn);
        TableSchema t = table(tn, List.of(
                col("id",  "integer", false),
                colPrec("n", "numeric", 15, 3, true),
                col("nt",  "ntext",   true)
        ), List.of("id"));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("NTEXT")),
                "Expected ntext → LONGTEXT warning");
        drop(tn);
    }

    /**
     * DE16: UNIQUE constraint → UNIQUE KEY in DDL, actually enforced by TiDB
     */
    @Test @Order(16)
    void de16_uniqueConstraintEnforced() throws Exception {
        String tn = "de_unique";
        drop(tn);
        ColumnSchema id    = col("id",    "int",     false);
        ColumnSchema email = colLen("email", "varchar", 200, false);

        TableSchema t = table(tn, List.of(id, email), List.of("id"));
        t.setUniqueConstraints(new java.util.LinkedHashMap<>(
                java.util.Map.of("uq_email", "email")));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));

        // Verify UNIQUE is actually enforced: inserting duplicate email must throw
        try (Statement st = tidbConn.createStatement()) {
            st.execute("INSERT INTO `" + tn + "` (id, email) VALUES (1, 'a@b.com')");
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO `" + tn + "` (id, email) VALUES (2, 'a@b.com')"),
                    "Duplicate email should violate UNIQUE constraint");
        }
        drop(tn);
    }

    /**
     * DE17: COLUMNSTORE index → discarded with warning, table still created
     */
    @Test @Order(17)
    void de17_columnstoreIndexDiscarded() throws Exception {
        String tn = "de_columnstore";
        drop(tn);
        IndexSchema cs = new IndexSchema();
        cs.setName("cs_idx"); cs.setColumnstore(true);
        cs.setColumns(List.of("id"));

        TableSchema t = table(tn, List.of(col("id", "int", false)), List.of("id"));
        t.setIndexes(List.of(cs));

        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("COLUMNSTORE")),
                "Expected COLUMNSTORE discard warning, got: " + r.getWarnings());
        drop(tn);
    }

    /**
     * DE18: GETDATE() default → CURRENT_TIMESTAMP; DDL executes, warning recorded
     */
    @Test @Order(18)
    void de18_defaultValueFunctionTranslation() throws Exception {
        String tn = "de_default_fn";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema created = col("created_at", "datetime", false);
        created.setDefaultValue("(getdate())");
        ColumnSchema updated = col("updated_at", "datetime", false);
        updated.setDefaultValue("(GETDATE())");

        TableSchema t = table(tn, List.of(id, created, updated), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("default value")),
                "Expected default value translation warning, got: " + r.getWarnings());

        // Verify the column has a DEFAULT in TiDB
        try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tn, "created_at")) {
            assertTrue(rs.next(), "created_at column must exist");
            String colDef = rs.getString("COLUMN_DEF");
            assertNotNull(colDef, "created_at should have a DEFAULT");
        }
        drop(tn);
    }

    /**
     * DE19: Column COMMENT clause is included in DDL and accepted by TiDB
     */
    @Test @Order(19)
    void de19_columnComment() throws Exception {
        String tn = "de_comment";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema status = col("status", "int", false);
        status.setComment("0=inactive 1=active");

        TableSchema t = table(tn, List.of(id, status), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(), r.getErrorMessage());
        assertTrue(tableExists(tn));

        // Verify TiDB stored the comment
        try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tn, "status")) {
            assertTrue(rs.next(), "status column must exist");
            String remarks = rs.getString("REMARKS");
            assertNotNull(remarks, "status column should have REMARKS/COMMENT");
            assertTrue(remarks.contains("inactive"), "COMMENT should be stored, got: " + remarks);
        }
        drop(tn);
    }

    /**
     * DE20: Regression — DATETIME(3) + GETDATE() default must generate
     * CURRENT_TIMESTAMP(3), not bare CURRENT_TIMESTAMP.
     * TiDB rejects precision mismatch with Error 1067.
     */
    @Test @Order(20)
    void de20_datetime3WithGetdateDefault_precisionMatched() throws Exception {
        String tn = "de_dt3_default";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema created = colDefaultScale("created_at", "datetime2", 3, false, "(getdate())");

        TableSchema t = table(tn, List.of(id, created), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "DATETIME(3) + GETDATE() should not produce Error 1067, got: " + r.getErrorMessage());
        assertTrue(tableExists(tn));

        // Verify TiDB stored the default
        try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tn, "created_at")) {
            assertTrue(rs.next());
            String def = rs.getString("COLUMN_DEF");
            assertNotNull(def, "created_at must have a DEFAULT");
            assertTrue(def.toUpperCase().contains("CURRENT_TIMESTAMP"),
                    "DEFAULT should be CURRENT_TIMESTAMP variant, got: " + def);
        }
        drop(tn);
    }

    /**
     * DE21: DATE column with GETDATE() default.
     * TypeMapper maps GETDATE() → CURRENT_TIMESTAMP regardless of column type.
     * TiDB rejects DEFAULT CURRENT_TIMESTAMP on a DATE column.
     * This test documents the current behavior (WARNING expected, not ERROR,
     * because SchemaConverter should either skip the default or translate it).
     * If this test fails with Status.ERROR, a bug exists in TypeMapper/SchemaConverter.
     */
    @Test @Order(21)
    void de21_dateColumnWithGetdateDefault() throws Exception {
        String tn = "de_date_default";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema d = colDefault("event_date", "date", true, "(getdate())");

        TableSchema t = table(tn, List.of(id, d), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "DATE + GETDATE() default should not produce a fatal DDL error. " +
                "If TiDB rejected DEFAULT CURRENT_TIMESTAMP on DATE column, " +
                "TypeMapper/SchemaConverter must be fixed to emit CURDATE() or drop the default. " +
                "Error: " + r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE22: TIME column with GETDATE() default.
     * TypeMapper maps GETDATE() → CURRENT_TIMESTAMP.
     * TiDB may reject DEFAULT CURRENT_TIMESTAMP on a TIME column.
     */
    @Test @Order(22)
    void de22_timeColumnWithGetdateDefault() throws Exception {
        String tn = "de_time_default";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema t_col = colDefault("event_time", "time", true, "(getdate())");

        TableSchema t = table(tn, List.of(id, t_col), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "TIME + GETDATE() default should not produce a fatal DDL error. " +
                "Error: " + r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE23: UNIQUEIDENTIFIER + NEWID() default.
     * TypeMapper maps NEWID() → UUID().
     * SchemaConverter emits: `guid` VARCHAR(36) DEFAULT UUID()
     * TiDB in MySQL 8.0 compat mode may require DEFAULT (UUID()).
     * This test documents whether bare UUID() is accepted.
     */
    @Test @Order(23)
    void de23_uniqueidentifierWithNewidDefault() throws Exception {
        String tn = "de_guid_default";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema guid = colDefault("guid", "uniqueidentifier", true, "(NEWID())");

        TableSchema t = table(tn, List.of(id, guid), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "UNIQUEIDENTIFIER + NEWID() default should not produce a fatal DDL error. " +
                "If TiDB rejected DEFAULT UUID(), SchemaConverter must wrap it as DEFAULT (UUID()). " +
                "Error: " + r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE24: DATETIME + SYSDATETIME() default.
     * TypeMapper maps SYSDATETIME() → CURRENT_TIMESTAMP(6).
     * DATETIME column (no precision) with DEFAULT CURRENT_TIMESTAMP(6):
     * TiDB may reject precision mismatch (DATETIME vs CURRENT_TIMESTAMP(6)).
     */
    @Test @Order(24)
    void de24_datetimeWithSysdatetimeDefault() throws Exception {
        String tn = "de_sysdatetime";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema ts = colDefault("logged_at", "datetime", false, "(SYSDATETIME())");

        TableSchema t = table(tn, List.of(id, ts), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "DATETIME + SYSDATETIME() should not produce a fatal DDL error. " +
                "Error: " + r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }

    /**
     * DE25: DATETIME2(6) + GETUTCDATE() default → UTC_TIMESTAMP().
     * Verifies UTC_TIMESTAMP() is accepted as DEFAULT on a DATETIME(6) column.
     */
    @Test @Order(25)
    void de25_datetime6WithGetutcdateDefault() throws Exception {
        String tn = "de_utcdate_default";
        drop(tn);
        ColumnSchema id = col("id", "int", false);
        ColumnSchema ts = colDefaultScale("synced_at", "datetime2", 6, false, "(GETUTCDATE())");

        TableSchema t = table(tn, List.of(id, ts), List.of("id"));
        ConversionResult r = exec(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, r.getStatus(),
                "DATETIME(6) + GETUTCDATE() should not produce a fatal DDL error. " +
                "Error: " + r.getErrorMessage());
        assertTrue(tableExists(tn));
        drop(tn);
    }
}
