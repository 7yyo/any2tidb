package com.tool.snapshot.sink;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for SinkRecordConverter — targets type dispatch edge cases,
 * unexpected JDBC value types, and numeric overflow.
 */
class SinkRecordConverterAdversarialTest {

    private SinkRecordConverter converter;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() {
        converter = new SinkRecordConverter();
        ps = mock(PreparedStatement.class);
    }

    // ── Short type → setInt ──────────────────────────────────────────────────

    @Test
    void shortValue_boundAsInt() throws SQLException {
        converter.bindAt(ps, 1, (short) 42);
        verify(ps).setInt(eq(1), eq(42));
    }

    // ── BigInteger → setBigDecimal ──────────────────────────────────────────

    @Test
    void bigInteger_boundAsBigDecimal() throws SQLException {
        BigInteger bigInt = new BigInteger("999999999999999999999");
        converter.bindAt(ps, 1, bigInt);
        verify(ps).setBigDecimal(eq(1), eq(new BigDecimal(bigInt)));
    }

    // ── ArrayList falls through to toString ────────────────────────────────────

    @Test
    void arrayList_fallsThroughToString() throws SQLException {
        converter.bindAt(ps, 1, List.of("a", "b"));
        verify(ps).setString(eq(1), eq("[a, b]"));
    }

    // ── HashMap falls through to toString ────────────────────────────────────

    @Test
    void hashMap_fallsThroughToString() throws SQLException {
        Map<String, Integer> map = Map.of("key", 1);
        converter.bindAt(ps, 1, map);
        verify(ps).setString(eq(1), eq("{key=1}"));
    }

    // ── Float precision loss when converted to Double ────────────────────────────

    @Test
    void floatPrecision_doubleCoercionLoss() throws SQLException {
        converter.bindAt(ps, 1, 0.1f);
        // Float → double → 0.10000000149011612
        verify(ps).setDouble(eq(1), argThat(d -> {
            double diff = Math.abs(d - 0.1f);
            return diff > 0.0; // not exact
        }));
    }

    // ── BigDecimal with very large scale ────────────────────────────────────────

    @Test
    void bigDecimal_largeScale_boundCorrectly() throws SQLException {
        BigDecimal bd = new BigDecimal("123456789.12345678901234567890");
        converter.bindAt(ps, 1, bd);
        verify(ps).setBigDecimal(eq(1), eq(bd));
    }

    // ── byte[] empty array ───────────────────────────────────────────────────

    @Test
    void emptyByteArray_boundAsEmptyBytes() throws SQLException {
        converter.bindAt(ps, 1, new byte[0]);
        verify(ps).setBytes(eq(1), eq(new byte[0]));
    }

    // ── null value → setNull(VARCHAR) ─────────────────────────────────────────

    @Test
    void nullValue_setNullVarchar() throws SQLException {
        converter.bindAt(ps, 1, null);
        verify(ps).setNull(eq(1), eq(Types.VARCHAR));
    }

    // ── empty String ────────────────────────────────────────────────────────

    @Test
    void emptyString_boundAsString() throws SQLException {
        converter.bindAt(ps, 1, "");
        verify(ps).setString(eq(1), eq(""));
    }

    // ── Boolean true/false ──────────────────────────────────────────────────

    @Test
    void booleanTrue_boundCorrectly() throws SQLException {
        converter.bindAt(ps, 1, true);
        verify(ps).setBoolean(eq(1), eq(true));
    }

    // ── bind(row, fields) with missing field in row ──────────────────────────────

    @Test
    void bindRow_missingField_setsNull() throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", 1);
        // row doesn't have "b" → row.get("b") returns null → setNull
        List<String> fields = List.of("a", "b");

        converter.bind(ps, row, fields);
        verify(ps).setNull(eq(2), eq(Types.VARCHAR));
    }

    // ── bind(row, fields) with null field name in row ────────────────────────────

    @Test
    void bindRow_nullFieldName_setsNull() throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", 1);
        row.put(null, 2);
        List<String> fields = List.of("a", null);

        converter.bind(ps, row, fields);
        verify(ps).setNull(eq(2), eq(Types.VARCHAR));
    }
}
