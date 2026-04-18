package com.tool.integration;

import com.tool.converter.SchemaConverter;
import com.tool.converter.TypeMapper;
import com.tool.extractor.SqlServerExtractor;
import com.tool.model.TableSchema;
import com.tool.verifier.SchemaVerifier;
import com.tool.verifier.VerifyResult;
import com.tool.writer.TiDBWriter;
import com.tool.ConversionResult;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SchemaVerifier.
 * Requires SQL Server on 127.0.0.1:1433 (sa/Test1234!) and TiDB on 127.0.0.1:4000 (root/no password).
 * Run with: mvn test -Pintegration -Dtest=VerifyIntegrationTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerifyIntegrationTest {

    private static final String SS_URL =
            "jdbc:sqlserver://127.0.0.1:1433;databaseName=testdb;encrypt=true;trustServerCertificate=true";
    private static final String TIDB_URL =
            "jdbc:mysql://127.0.0.1:4000/testdb?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    private static Connection ssConn;
    private static Connection tidbConn;

    private final SchemaVerifier verifier = new SchemaVerifier();
    private final SqlServerExtractor extractor = new SqlServerExtractor();
    private final SchemaConverter converter = new SchemaConverter(new TypeMapper());
    private final TiDBWriter writer = new TiDBWriter();

    @BeforeAll
    static void connect() throws Exception {
        ssConn = DriverManager.getConnection(SS_URL, "sa", "Test1234!");
        tidbConn = DriverManager.getConnection(TIDB_URL, "root", "");
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (ssConn != null) ssConn.close();
        if (tidbConn != null) tidbConn.close();
    }

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

    private void migrate(String tableName) throws Exception {
        TableSchema schema = extractor.extractTable(ssConn, "dbo", tableName);
        ConversionResult result = new ConversionResult("dbo." + tableName);
        String ddl = converter.toCreateTableDDL(schema, result, false);
        writer.executeDDL(tidbConn, ddl, result);
    }

    /**
     * VT01: 完全一致的表 → OK，所有指标 MS值==TiDB值
     */
    @Test
    @Order(1)
    void vt01_cleanTable_isOK() throws Exception {
        String t = "vt_clean";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.vt_clean (
                id   INT NOT NULL IDENTITY(1,1) PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                age  INT
            );
            CREATE INDEX IX_vt_name ON dbo.vt_clean (name);
            """);
        migrate(t);

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", t);
        assertFalse(r.isMismatch(), "全量迁移后不应有 MISMATCH，diffLines=" + r.diffLines());
        assertEquals(3, r.msCols());
        assertEquals(3, r.tidbCols());
        assertEquals(List.of("id"), r.msPkCols());
        assertEquals(List.of("id"), r.tidbPkCols());
        assertEquals(1, r.msIdx());
        assertEquals(1, r.tidbIdx());
        assertEquals(0, r.msFk());
        assertEquals("id", r.msAiCol());
        assertEquals("id", r.tidbAiCol());

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * VT02: 手动在 TiDB 删一列后 → MISMATCH，missing cols 中包含该列
     */
    @Test
    @Order(2)
    void vt02_missingColInTidb_isMismatch() throws Exception {
        String t = "vt_missing_col";
        dropSSTable(t); dropTiDBTable(t);
        executeSS("""
            CREATE TABLE dbo.vt_missing_col (
                id   INT NOT NULL PRIMARY KEY,
                name VARCHAR(50),
                note VARCHAR(200)
            )
            """);
        migrate(t);
        // 模拟 TiDB 侧少一列
        executeTiDB("ALTER TABLE `" + t + "` DROP COLUMN `note`");

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", t);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols") && l.contains("note")));

        dropSSTable(t); dropTiDBTable(t);
    }

    /**
     * VT03: 有外键的表 → FK 显示 MS 侧数量，但不算 MISMATCH
     */
    @Test
    @Order(3)
    void vt03_foreignKey_notMismatch() throws Exception {
        dropSSTable("vt_fk_child"); dropSSTable("vt_fk_parent");
        dropTiDBTable("vt_fk_child"); dropTiDBTable("vt_fk_parent");
        executeSS("""
            CREATE TABLE dbo.vt_fk_parent (id INT NOT NULL PRIMARY KEY);
            CREATE TABLE dbo.vt_fk_child (
                id INT NOT NULL PRIMARY KEY,
                pid INT,
                CONSTRAINT FK_vt FOREIGN KEY (pid) REFERENCES dbo.vt_fk_parent(id)
            );
            """);
        migrate("vt_fk_parent");
        migrate("vt_fk_child");

        VerifyResult r = verifier.verify(ssConn, tidbConn, "dbo", "vt_fk_child");
        assertEquals(1, r.msFk(), "MS 侧应有 1 个外键");
        assertFalse(r.isMismatch(), "外键不参与 MISMATCH 判定");

        dropSSTable("vt_fk_child"); dropSSTable("vt_fk_parent");
        dropTiDBTable("vt_fk_child"); dropTiDBTable("vt_fk_parent");
    }

    /**
     * VT04: verifyAll 批量校验
     */
    @Test
    @Order(4)
    void vt04_verifyAll() throws Exception {
        String t1 = "vt_all_a", t2 = "vt_all_b";
        dropSSTable(t1); dropSSTable(t2);
        dropTiDBTable(t1); dropTiDBTable(t2);
        executeSS("CREATE TABLE dbo.vt_all_a (id INT NOT NULL PRIMARY KEY, val VARCHAR(50))");
        executeSS("CREATE TABLE dbo.vt_all_b (id INT NOT NULL PRIMARY KEY, num BIGINT)");
        migrate(t1); migrate(t2);

        List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn,
                List.of(new String[]{"dbo", t1}, new String[]{"dbo", t2}));
        assertEquals(2, results.size());
        results.forEach(r -> assertFalse(r.isMismatch(), r.fullTableName() + " should be OK"));

        dropSSTable(t1); dropSSTable(t2);
        dropTiDBTable(t1); dropTiDBTable(t2);
    }
}
