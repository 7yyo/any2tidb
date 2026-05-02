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
}
