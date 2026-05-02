package com.tool.compare;

import org.junit.jupiter.api.Test;
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
}
