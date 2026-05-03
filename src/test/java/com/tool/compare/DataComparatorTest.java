package com.tool.compare;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DataComparatorTest {

    @Test
    void configDefaultsShouldSetSensibleValues() {
        ComparisonConfig c = ComparisonConfig.defaults("mydb");
        assertThat(c.catalog()).isEqualTo("mydb");
        assertThat(c.targetCatalog()).isNull();
        assertThat(c.tables()).isEmpty();
        assertThat(c.batchSize()).isEqualTo(5000);
        assertThat(c.maxMismatchRows()).isEqualTo(50);
    }

    @Test
    void configShouldRejectNullCatalog() {
        assertThatThrownBy(() -> new ComparisonConfig(null, null, List.of(), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void configShouldRejectBlankCatalog() {
        assertThatThrownBy(() -> new ComparisonConfig("  ", null, List.of(), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void configShouldClampNegativeBatchSize() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), -1, 10);
        assertThat(c.batchSize()).isEqualTo(5000);
    }

    @Test
    void configShouldClampNegativeMaxMismatch() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), 1000, -5);
        assertThat(c.maxMismatchRows()).isEqualTo(50);
    }

    @Test
    void configShouldAcceptZeroMaxMismatch() {
        ComparisonConfig c = new ComparisonConfig("db", null, List.of(), 1000, 0);
        assertThat(c.maxMismatchRows()).isEqualTo(0);
    }

    // ── ValueNormalizer ─────────────────────────────────────────────────

    @Test
    void normalizeNullShouldReturnNull() {
        assertThat(ValueNormalizer.normalize(null)).isNull();
    }

    @Test
    void normalizeBigDecimalShouldStripTrailingZeros() {
        assertThat(ValueNormalizer.normalize(new java.math.BigDecimal("1.00")))
                .isEqualTo("1");
        assertThat(ValueNormalizer.normalize(new java.math.BigDecimal("99.950")))
                .isEqualTo("99.95");
    }

    @Test
    void normalizeStringShouldUnifyLineEndings() {
        assertThat(ValueNormalizer.normalize("a\r\nb"))
                .isEqualTo("a\nb");
        assertThat(ValueNormalizer.normalize("a\rb"))
                .isEqualTo("a\nb");
        assertThat(ValueNormalizer.normalize("a\nb"))
                .isEqualTo("a\nb");
    }

    @Test
    void normalizeBooleanShouldMapToNumeric() {
        assertThat(ValueNormalizer.normalize(true)).isEqualTo("1");
        assertThat(ValueNormalizer.normalize(false)).isEqualTo("0");
    }

    @Test
    void normalizeByteArrayShouldBase64Encode() {
        byte[] data = {0x48, 0x65, 0x6C, 0x6C, 0x6F};
        assertThat(ValueNormalizer.normalize(data)).isEqualTo("SGVsbG8=");
    }

    // ── JdbcDataComparator integration tests ─────────────────────────────

    private static Connection newH2(String name) {
        try {
            return DriverManager.getConnection(
                    "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void exec(Connection c, String sql) {
        try (var s = c.createStatement()) { s.execute(sql); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void shouldMatchIdenticalTables() {
        Connection src = newH2("src_match");
        Connection tgt = newH2("tgt_match");

        for (Connection c : new Connection[]{src, tgt}) {
            exec(c, "CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(50), price DECIMAL(10,2))");
            exec(c, "INSERT INTO orders VALUES (1, 'Apple', 1.50)");
            exec(c, "INSERT INTO orders VALUES (2, 'Banana', 2.00)");
            exec(c, "INSERT INTO orders VALUES (3, 'Orange', 1.75)");
        }

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_match"));

        assertThat(r.totalTables()).isEqualTo(1);
        assertThat(r.matchedTables()).isEqualTo(1);
        assertThat(r.mismatchedTables()).isEqualTo(0);
        assertThat(r.hasMismatches()).isFalse();
    }

    @Test
    void shouldDetectRowCountMismatch() {
        Connection src = newH2("src_rc");
        Connection tgt = newH2("tgt_rc");

        exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        exec(src, "INSERT INTO t VALUES (1,10), (2,20), (3,30)");

        exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        exec(tgt, "INSERT INTO t VALUES (1,10), (2,20)");

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_rc"));

        assertThat(r.mismatchedTables()).isEqualTo(1);
        TableComparison tc = r.tables().get(0);
        assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
        assertThat(tc.rowCountSrc()).isEqualTo(3);
        assertThat(tc.rowCountTgt()).isEqualTo(2);
    }

    @Test
    void shouldDetectMissingAndExtraRows() {
        Connection src = newH2("src_me");
        Connection tgt = newH2("tgt_me");

        exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        exec(src, "INSERT INTO t VALUES (1,10), (2,20), (3,30)");

        exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        exec(tgt, "INSERT INTO t VALUES (1,10), (2,20), (4,40)");

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_me"));

        TableComparison tc = r.tables().get(0);
        assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
        assertThat(tc.missingInTarget()).contains("(ID=3)");
        assertThat(tc.extraInTarget()).contains("(ID=4)");
    }

    @Test
    void shouldDetectValueDifferences() {
        Connection src = newH2("src_val");
        Connection tgt = newH2("tgt_val");

        exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT, name VARCHAR(20))");
        exec(src, "INSERT INTO t VALUES (1, 100, 'foo')");

        exec(tgt, "CREATE TABLE t (id INT PRIMARY KEY, val INT, name VARCHAR(20))");
        exec(tgt, "INSERT INTO t VALUES (1, 200, 'foo')");

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_val"));

        TableComparison tc = r.tables().get(0);
        assertThat(tc.status()).isEqualTo(TableComparison.Status.MISMATCHED);
        assertThat(tc.columnDiffs()).isNotEmpty();
        ColumnDiff diff = tc.columnDiffs().get(0);
        assertThat(diff.pkValues()).containsEntry("ID", "1");
        assertThat(diff.diffs()).anyMatch(d -> d.column().equals("VAL")
                && d.srcValue().equals("100") && d.tgtValue().equals("200"));
    }

    @Test
    void shouldSkipTableMissingInTarget() {
        Connection src = newH2("src_skip");
        Connection tgt = newH2("tgt_skip");

        exec(src, "CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        exec(src, "INSERT INTO t VALUES (1,10)");

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_skip"));

        assertThat(r.totalTables()).isEqualTo(1);
        assertThat(r.skippedTables()).isEqualTo(1);
        assertThat(r.matchedTables()).isEqualTo(0);
    }

    @Test
    void shouldSkipTableWithoutPrimaryKey() {
        Connection src = newH2("src_nopk");
        Connection tgt = newH2("tgt_nopk");

        for (Connection c : new Connection[]{src, tgt}) {
            exec(c, "CREATE TABLE t (a INT, b INT)");
            exec(c, "INSERT INTO t VALUES (1,2)");
        }

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                ComparisonConfig.defaults("src_nopk"));

        assertThat(r.skippedTables()).isEqualTo(1);
        assertThat(r.tables().get(0).status()).isEqualTo(TableComparison.Status.SKIPPED);
    }

    @Test
    void shouldFilterTablesByConfig() {
        Connection src = newH2("src_filter");
        Connection tgt = newH2("tgt_filter");

        for (Connection c : new Connection[]{src, tgt}) {
            exec(c, "CREATE TABLE orders (id INT PRIMARY KEY, v INT)");
            exec(c, "INSERT INTO orders VALUES (1,100)");
            exec(c, "CREATE TABLE items (id INT PRIMARY KEY, v INT)");
            exec(c, "INSERT INTO items VALUES (1,200)");
        }

        ComparisonReport r = new JdbcDataComparator().compare(src, tgt,
                new ComparisonConfig("src_filter", null,
                        List.<String[]>of(new String[]{"PUBLIC", "orders"}), 5000, 50));

        assertThat(r.totalTables()).isEqualTo(1);
        assertThat(r.tables().get(0).fullName()).contains("orders");
    }
}
