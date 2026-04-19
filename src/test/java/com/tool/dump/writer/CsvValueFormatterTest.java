package com.tool.dump.writer;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvValueFormatterTest {

    // ── NULL ──────────────────────────────────────────────────────────────────

    @Test
    void nullValue_returnsBackslashN() {
        assertEquals("\\N", CsvValueFormatter.format(null));
    }

    // ── Strings ───────────────────────────────────────────────────────────────

    @Test
    void plainString_wrapsInDoubleQuotes() {
        assertEquals("\"hello\"", CsvValueFormatter.format("hello"));
    }

    @Test
    void stringWithDoubleQuote_escapesAsDoubleDouble() {
        // RFC 4180: internal " → ""
        assertEquals("\"say \"\"hi\"\"\"", CsvValueFormatter.format("say \"hi\""));
    }

    @Test
    void stringWithComma_wrapsInDoubleQuotes_noEscape() {
        assertEquals("\"a,b\"", CsvValueFormatter.format("a,b"));
    }

    @Test
    void stringWithNewline_wrapsInDoubleQuotes_preservesNewline() {
        assertEquals("\"line1\nline2\"", CsvValueFormatter.format("line1\nline2"));
    }

    @Test
    void emptyString_wrapsInDoubleQuotes() {
        assertEquals("\"\"", CsvValueFormatter.format(""));
    }

    // ── Numerics ──────────────────────────────────────────────────────────────

    @Test
    void integer_wrapsInDoubleQuotes() {
        assertEquals("\"42\"", CsvValueFormatter.format(42));
    }

    @Test
    void longValue_wrapsInDoubleQuotes() {
        assertEquals("\"9999999999\"", CsvValueFormatter.format(9_999_999_999L));
    }

    @Test
    void bigDecimal_wrapsInDoubleQuotes() {
        assertEquals("\"3.14159\"", CsvValueFormatter.format(new BigDecimal("3.14159")));
    }

    @Test
    void doubleValue_wrapsInDoubleQuotes() {
        assertEquals("\"1.5\"", CsvValueFormatter.format(1.5d));
    }

    // ── Boolean / bit ─────────────────────────────────────────────────────────

    @Test
    void booleanTrue_outputs1() {
        assertEquals("\"1\"", CsvValueFormatter.format(true));
    }

    @Test
    void booleanFalse_outputs0() {
        assertEquals("\"0\"", CsvValueFormatter.format(false));
    }

    // ── Date / Time ───────────────────────────────────────────────────────────

    @Test
    void sqlDate_formatsAsIsoDate() {
        Date d = Date.valueOf("2024-01-15");
        assertEquals("\"2024-01-15\"", CsvValueFormatter.format(d));
    }

    @Test
    void sqlTime_formatsAsHHmmss() {
        Time t = Time.valueOf("12:30:45");
        assertEquals("\"12:30:45\"", CsvValueFormatter.format(t));
    }

    @Test
    void sqlTimestamp_formatsAsIsoWithMillis() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:30:00.123");
        assertEquals("\"2024-01-15 12:30:00.123\"", CsvValueFormatter.format(ts));
    }

    @Test
    void sqlTimestamp_zeroMillis_formatsWithDotZeroes() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:30:00.0");
        assertEquals("\"2024-01-15 12:30:00.000\"", CsvValueFormatter.format(ts));
    }

    // ── Binary ────────────────────────────────────────────────────────────────

    @Test
    void byteArray_formatsAsBase64() {
        byte[] data = {0x01, 0x02, 0x03};
        String expected = "\"" + Base64.getEncoder().encodeToString(data) + "\"";
        assertEquals(expected, CsvValueFormatter.format(data));
    }

    @Test
    void emptyByteArray_formatsAsEmptyBase64() {
        assertEquals("\"\"", CsvValueFormatter.format(new byte[0]));
    }
}
