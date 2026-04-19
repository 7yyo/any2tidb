package com.tool.integration;

import com.tool.common.model.ConversionResult;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.converter.TypeMapper;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.common.model.TableSchema;
import com.tool.schema.writer.TiDBWriter;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: requires a live SQL Server on 127.0.0.1:1433 (sa/Test1234!)
 * and TiDB on 127.0.0.1:4000 (root/no password).
 *
 * Run with: mvn test -Dtest=IntegrationTest -Pintegration
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {

    private static final String SS_URL =
            "jdbc:sqlserver://127.0.0.1:1433;databaseName=testdb;encrypt=true;trustServerCertificate=true";
    private static final String SS_USER = "sa";
    private static final String SS_PASS = "Test1234!";

    private static final String TIDB_URL =
            "jdbc:mysql://127.0.0.1:4000/testdb?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    private static final String TIDB_USER = "root";
    private static final String TIDB_PASS = "";

    private static Connection ssConn;
    private static Connection tidbConn;

    private final SqlServerExtractor extractor = new SqlServerExtractor();
    private final SchemaConverter converter = new SchemaConverter(new TypeMapper());
    private final TiDBWriter writer = new TiDBWriter();

    @BeforeAll
    static void connect() throws Exception {
        ssConn = DriverManager.getConnection(SS_URL, SS_USER, SS_PASS);
        tidbConn = DriverManager.getConnection(TIDB_URL, TIDB_USER, TIDB_PASS);
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (ssConn != null) ssConn.close();
        if (tidbConn != null) tidbConn.close();
    }

    // -----------------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------------

    private void executeSS(String sql) throws SQLException {
        try (Statement st = ssConn.createStatement()) { st.execute(sql); }
    }

    private void executeTiDB(String sql) throws SQLException {
        try (Statement st = tidbConn.createStatement()) { st.execute(sql); }
    }

    private void dropSSTable(String name) {
        try { executeSS("IF OBJECT_ID('dbo." + name + "', 'U') IS NOT NULL DROP TABLE dbo." + name); }
        catch (SQLException ignored) {}
    }

    private void dropTiDBTable(String name) {
        try { executeTiDB("DROP TABLE IF EXISTS `" + name + "`"); }
        catch (SQLException ignored) {}
    }

    /** 迁移一张表，返回 ConversionResult */
    private ConversionResult migrate(String tableName, boolean dropIfExists) throws Exception {
        TableSchema schema = extractor.extractTable(ssConn, "dbo", tableName);
        ConversionResult result = new ConversionResult("dbo." + tableName);
        String ddl = converter.toCreateTableDDL(schema, result, dropIfExists);
        System.out.println("\n=== DDL for " + tableName + " ===\n" + ddl);
        writer.executeDDL(tidbConn, ddl, result);
        return result;
    }

    /** 获取 TiDB 列信息 Map<列名, 类型> */
    private Map<String, String> tidbColumns(String tableName) throws SQLException {
        Map<String, String> cols = new LinkedHashMap<>();
        try (ResultSet rs = tidbConn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) cols.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
        }
        return cols;
    }

    /** 获取 TiDB 索引信息 */
    private Set<String> tidbIndexNames(String tableName) throws SQLException {
        Set<String> idxs = new HashSet<>();
        try (ResultSet rs = tidbConn.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && !name.equalsIgnoreCase("PRIMARY")) idxs.add(name);
            }
        }
        return idxs;
    }

    // -----------------------------------------------------------------------
    // 测试用例
    // -----------------------------------------------------------------------

    /**
     * TC01: 基础类型映射 — int, bigint, varchar, nvarchar, datetime, bit, decimal
     */
    @Test
    @Order(1)
    void tc01_basicTypes() throws Exception {
        String t = "it_basic_types";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_basic_types (
                id          INT            NOT NULL IDENTITY(1,1),
                big_col     BIGINT,
                flag        BIT            NOT NULL DEFAULT 0,
                price       DECIMAL(10,2),
                name_en     VARCHAR(100)   NOT NULL,
                name_cn     NVARCHAR(100),
                created_at  DATETIME       NOT NULL DEFAULT GETDATE(),
                PRIMARY KEY (id)
            )
            """);

        ConversionResult result = migrate(t, false);
        assertEquals(ConversionResult.Status.WARN, result.getStatus(), "GETDATE 默认值应产生 WARN");

        Map<String, String> cols = tidbColumns(t);
        assertFalse(cols.isEmpty(), "TiDB 表应存在");
        assertEquals("INT", cols.get("id"));
        assertEquals("BIGINT", cols.get("big_col"));
        assertTrue("TINYINT".equals(cols.get("flag")) || "BIT".equals(cols.get("flag")),
                "flag 应为 TINYINT 或 BIT，实际: " + cols.get("flag"));
        assertEquals("DECIMAL", cols.get("price"));
        assertEquals("VARCHAR", cols.get("name_en"));
        assertEquals("VARCHAR", cols.get("name_cn"));
        assertEquals("DATETIME", cols.get("created_at"));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC02: AUTO_INCREMENT + PRIMARY KEY
     */
    @Test
    @Order(2)
    void tc02_autoIncrementPK() throws Exception {
        String t = "it_pk_auto";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_pk_auto (
                id   INT NOT NULL IDENTITY(1,1) PRIMARY KEY,
                val  VARCHAR(50)
            )
            """);

        ConversionResult result = migrate(t, false);
        assertEquals(ConversionResult.Status.OK, result.getStatus());

        // 验证 AUTO_INCREMENT 生效：插入两行
        executeTiDB("INSERT INTO `" + t + "` (val) VALUES ('a')");
        executeTiDB("INSERT INTO `" + t + "` (val) VALUES ('b')");
        try (Statement st = tidbConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM `" + t + "` ORDER BY id")) {
            assertTrue(rs.next()); int id1 = rs.getInt(1);
            assertTrue(rs.next()); int id2 = rs.getInt(1);
            assertTrue(id2 > id1, "id 应自增");
        }

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC03: 复合主键 + UNIQUE 约束
     */
    @Test
    @Order(3)
    void tc03_compositePkAndUnique() throws Exception {
        String t = "it_comp_pk";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_comp_pk (
                tenant_id  INT         NOT NULL,
                user_id    INT         NOT NULL,
                email      VARCHAR(200) NOT NULL,
                CONSTRAINT PK_comp PRIMARY KEY (tenant_id, user_id),
                CONSTRAINT UQ_email UNIQUE (email)
            )
            """);

        ConversionResult result = migrate(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, result.getStatus());

        // 插入一行，再插同 email 应报错
        executeTiDB("INSERT INTO `" + t + "` VALUES (1,1,'a@b.com')");
        assertThrows(SQLException.class,
            () -> executeTiDB("INSERT INTO `" + t + "` VALUES (1,2,'a@b.com')"),
            "UNIQUE 约束应生效");

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC04: 普通索引 + UNIQUE 索引
     */
    @Test
    @Order(4)
    void tc04_indexes() throws Exception {
        String t = "it_indexes";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_indexes (
                id    INT NOT NULL PRIMARY KEY,
                code  VARCHAR(50),
                name  NVARCHAR(100)
            );
            CREATE INDEX IX_code ON dbo.it_indexes (code);
            CREATE UNIQUE INDEX IX_name ON dbo.it_indexes (name);
            """);

        ConversionResult result = migrate(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, result.getStatus());

        Set<String> idxs = tidbIndexNames(t);
        assertTrue(idxs.contains("IX_code"), "普通索引应存在");
        assertTrue(idxs.contains("IX_name"), "UNIQUE 索引应存在");

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC05: INCLUDE 列的索引（TiDB 不支持，应 WARN 并丢弃 INCLUDE）
     */
    @Test
    @Order(5)
    void tc05_indexWithInclude() throws Exception {
        String t = "it_include_idx";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_include_idx (
                id    INT NOT NULL PRIMARY KEY,
                code  VARCHAR(50),
                name  VARCHAR(100),
                descr VARCHAR(200)
            );
            CREATE INDEX IX_include ON dbo.it_include_idx (code) INCLUDE (name, descr);
            """);

        ConversionResult result = migrate(t, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("INCLUDE")),
                "应有 INCLUDE 列 WARN");
        // 索引本身仍应存在（只是不含 INCLUDE 列）
        assertTrue(tidbIndexNames(t).contains("IX_include"), "索引主体应保留");

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC06: 外键（应全部丢弃 + WARN）
     */
    @Test
    @Order(6)
    void tc06_foreignKeyDiscarded() throws Exception {
        dropSSTable("it_fk_child"); dropSSTable("it_fk_parent");
        dropTiDBTable("it_fk_child"); dropTiDBTable("it_fk_parent");

        executeSS("""
            CREATE TABLE dbo.it_fk_parent (
                id INT NOT NULL PRIMARY KEY
            );
            CREATE TABLE dbo.it_fk_child (
                id        INT NOT NULL PRIMARY KEY,
                parent_id INT,
                CONSTRAINT FK_parent FOREIGN KEY (parent_id) REFERENCES dbo.it_fk_parent(id)
            );
            """);

        ConversionResult result = migrate("it_fk_child", false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("foreign key")),
                "外键应有 WARN");

        // TiDB 中不应有 FK 约束：插入孤儿行不应报错
        migrate("it_fk_parent", false);
        assertDoesNotThrow(() -> executeTiDB("INSERT INTO `it_fk_child` VALUES (1, 99999)"),
                "TiDB 中外键已丢弃，插入孤儿行应成功");

        dropSSTable("it_fk_child"); dropSSTable("it_fk_parent");
        dropTiDBTable("it_fk_child"); dropTiDBTable("it_fk_parent");
    }

    /**
     * TC07: 分区表（转为普通表 + WARN）
     */
    @Test
    @Order(7)
    void tc07_partitionedTable() throws Exception {
        String t = "it_partitioned";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE PARTITION FUNCTION pf_int (INT) AS RANGE LEFT FOR VALUES (100, 200, 300);
            CREATE PARTITION SCHEME ps_int AS PARTITION pf_int ALL TO ([PRIMARY]);
            CREATE TABLE dbo.it_partitioned (
                id  INT NOT NULL,
                val VARCHAR(50),
                PRIMARY KEY (id)
            ) ON ps_int(id);
            """);

        ConversionResult result = migrate(t, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("partitioned")),
                "分区表应有 WARN");
        // 表结构迁移成功，验证列存在
        Map<String, String> cols = tidbColumns(t);
        assertFalse(cols.isEmpty(), "TiDB 表应存在");
        assertEquals("INT", cols.get("id"));
        assertEquals("VARCHAR", cols.get("val"));

        dropSSTable(t); dropTiDBTable(t);
        try { executeSS("DROP PARTITION SCHEME ps_int"); } catch (SQLException ignored) {}
        try { executeSS("DROP PARTITION FUNCTION pf_int"); } catch (SQLException ignored) {}
    }

    /**
     * TC08: drop-if-exists 模式（重复迁移不报错）
     */
    @Test
    @Order(8)
    void tc08_dropIfExists() throws Exception {
        String t = "it_drop_rerun";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_drop_rerun (
                id  INT NOT NULL PRIMARY KEY,
                val VARCHAR(50)
            )
            """);

        migrate(t, false);
        // 第二次用 dropIfExists=true，不应报错
        ConversionResult result2 = migrate(t, true);
        assertNotEquals(ConversionResult.Status.ERROR, result2.getStatus(),
                "dropIfExists 模式重复迁移不应报错");
        // 验证表结构完整
        Map<String, String> cols2 = tidbColumns(t);
        assertEquals("INT", cols2.get("id"));
        assertEquals("VARCHAR", cols2.get("val"));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC09: MONEY / SMALLMONEY 类型（WARN + 转 DECIMAL）
     */
    @Test
    @Order(9)
    void tc09_moneyType() throws Exception {
        String t = "it_money";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_money (
                id     INT NOT NULL PRIMARY KEY,
                price  MONEY,
                tax    SMALLMONEY
            )
            """);

        ConversionResult result = migrate(t, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("MONEY")),
                "MONEY 应有 WARN");

        Map<String, String> cols = tidbColumns(t);
        assertEquals("DECIMAL", cols.get("price"));
        assertEquals("DECIMAL", cols.get("tax"));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC10: NVARCHAR(MAX) / VARCHAR(MAX) / TEXT → LONGTEXT
     */
    @Test
    @Order(10)
    void tc10_largeTextTypes() throws Exception {
        String t = "it_largetext";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_largetext (
                id    INT NOT NULL PRIMARY KEY,
                c1    NVARCHAR(MAX),
                c2    VARCHAR(MAX),
                c3    TEXT,
                c4    NTEXT
            )
            """);

        ConversionResult result = migrate(t, false);
        assertTrue(result.getWarnings().size() >= 3, "MAX/TEXT 类型应有多个 WARN");

        Map<String, String> cols = tidbColumns(t);
        assertEquals("LONGTEXT", cols.get("c1"));
        assertEquals("LONGTEXT", cols.get("c2"));
        assertEquals("LONGTEXT", cols.get("c3"));
        assertEquals("LONGTEXT", cols.get("c4"));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC11: UNIQUEIDENTIFIER → VARCHAR(36)，XML → LONGTEXT
     */
    @Test
    @Order(11)
    void tc11_guidAndXml() throws Exception {
        String t = "it_guid_xml";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_guid_xml (
                id    INT           NOT NULL PRIMARY KEY,
                guid  UNIQUEIDENTIFIER DEFAULT NEWID(),
                meta  XML
            )
            """);

        ConversionResult result = migrate(t, false);
        assertNotEquals(ConversionResult.Status.ERROR, result.getStatus());

        Map<String, String> cols = tidbColumns(t);
        assertEquals("VARCHAR", cols.get("guid"));
        assertEquals("LONGTEXT", cols.get("meta"));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * TC12: VARBINARY / IMAGE → LONGBLOB
     */
    @Test
    @Order(12)
    void tc12_binaryTypes() throws Exception {
        String t = "it_binary";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.it_binary (
                id    INT NOT NULL PRIMARY KEY,
                thumb VARBINARY(MAX),
                photo IMAGE
            )
            """);

        ConversionResult result = migrate(t, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("LONGBLOB")));

        Map<String, String> cols = tidbColumns(t);
        assertEquals("LONGBLOB", cols.get("thumb"));
        assertEquals("LONGBLOB", cols.get("photo"));

        dropSSTable(t); dropTiDBTable(t);
    }
}
