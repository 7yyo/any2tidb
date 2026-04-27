package com.tool.dump.writer;

import org.junit.jupiter.api.Test;

import java.sql.Time;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for CsvValueFormatter — targets edge cases that produce
 * invalid CSV, silent data loss, or output that Dumpling/Lightning cannot import.
 */
class CsvValueFormatterAdversarialTest {

    // ── Timestamp: sub-millisecond precision rounded (not truncated) ─────────

    @Test
    void timestamp_nanos999999999_roundedTo1000ms_overflowsToNextSecond() {
        // 999_999_999 nanos → (999_999_999 + 500_000) / 1_000_000 = 1000 → overflow
        // carry: +1 second, millis = 0
        Timestamp ts = new Timestamp(2024 - 1900, 0, 15, 12, 30, 0, 999_999_999);
        String result = CsvValueFormatter.format(ts);
        assertEquals("\"2024-01-15 12:30:01.000\"", result,
                "Nanos 999999999 rounds to 1000ms → carries to next second");
    }

    @Test
    void timestamp_nanos500000_roundsUpTo1ms() {
        // 500_000 nanos = 0.5ms → (500_000 + 500_000) / 1_000_000 = 1
        Timestamp ts = new Timestamp(2024 - 1900, 0, 15, 12, 30, 0, 500_000);
        String result = CsvValueFormatter.format(ts);
        assertEquals("\"2024-01-15 12:30:00.001\"", result,
                "500µs rounds UP to 1ms (half-up rounding)");
    }

    @Test
    void timestamp_nanos499999_roundsDownTo0ms() {
        // 499_999 nanos → (499_999 + 500_000) / 1_000_000 = 0
        Timestamp ts = new Timestamp(2024 - 1900, 0, 15, 12, 30, 0, 499_999);
        String result = CsvValueFormatter.format(ts);
        assertEquals("\"2024-01-15 12:30:00.000\"", result,
                "499999ns rounds DOWN to 0ms");
    }

    // ── Time: fractional seconds stripped (fixed) ────────────────────────────

    @Test
    void time_noFractionalSeconds_cleanOutput() {
        Time t = Time.valueOf("12:30:45");
        assertEquals("\"12:30:45\"", CsvValueFormatter.format(t));
    }

    @Test
    void time_withNanos_fractionalStripped() {
        // Even if a JDBC driver sets nanos on Time, fractional seconds are now stripped
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:30:45.123456789");
        Time t = new Time(ts.getTime());
        String result = CsvValueFormatter.format(t);
        assertFalse(result.contains("."), "Fractional seconds should be stripped from Time");
        assertTrue(result.contains("12:30:45"));
    }

    // ── String containing literal \N ─────────────────────────────────────────

    @Test
    void stringLiteralBackslashN_quotedNotConfusedWithNull() {
        // The literal two-character string "\N" should be quoted, not treated as NULL
        // This is fine because format() wraps all non-null values in quotes
        assertEquals("\"\\N\"", CsvValueFormatter.format("\\N"),
                "Literal backslash-N string must be quoted, not confused with NULL literal \\N");
    }

    // ── String containing CRLF ───────────────────────────────────────────────

    @Test
    void stringContainingCRLF_embeddedInQuotedField() {
        // RFC 4180 allows CRLF inside quoted fields, but many parsers break
        // Dumpling/Lightning may or may not handle this
        String result = CsvValueFormatter.format("line1\r\nline2");
        assertEquals("\"line1\r\nline2\"", result,
                "CRLF inside string is embedded in quoted field — may break CSV parsers");
    }

    // ── String containing NUL byte ───────────────────────────────────────────

    @Test
    void stringContainingNulByte_embeddedInOutput() {
        String result = CsvValueFormatter.format("a\0b");
        assertEquals("\"a\0b\"", result,
                "NUL byte embedded in CSV — may cause issues with C-based parsers");
    }

    // ── Double NaN / Infinity — not valid SQL values ─────────────────────────

    @Test
    void doubleNaN_producesQuotedNaN() {
        assertEquals("\"NaN\"", CsvValueFormatter.format(Double.NaN),
                "NaN is not a valid SQL numeric — TiDB Lightning will reject this");
    }

    @Test
    void doublePositiveInfinity_producesQuotedInfinity() {
        assertEquals("\"Infinity\"", CsvValueFormatter.format(Double.POSITIVE_INFINITY),
                "Infinity is not a valid SQL numeric — TiDB Lightning will reject this");
    }

    @Test
    void doubleNegativeInfinity_producesQuotedNegativeInfinity() {
        assertEquals("\"-Infinity\"", CsvValueFormatter.format(Double.NEGATIVE_INFINITY),
                "-Infinity is not a valid SQL numeric");
    }

    // ── Unknown JDBC type falls through to toString() ────────────────────────

    @Test
    void unknownJdbcType_clob_producesObjectToString() {
        // If JDBC returns a Clob, it falls through to toString()
        // This produces implementation-specific output, not the actual Clob content
        Object clob = new Object() {
            @Override
            public String toString() {
                return "com.microsoft.sqlserver.jdbc.ClobImpl@1a2b3c4d";
            }
        };
        String result = CsvValueFormatter.format(clob);
        assertTrue(result.contains("ClobImpl"),
                "Unknown JDBC type falls through to toString() — produces class name, not data");
        assertTrue(result.startsWith("\"") && result.endsWith("\""));
    }

    // ── Float type (not Double) ──────────────────────────────────────────────

    @Test
    void floatValue_fallsThroughToToString() {
        // Float is not explicitly handled — falls through to toString()
        // This is fine but worth documenting
        assertEquals("\"1.5\"", CsvValueFormatter.format(1.5f));
    }

    // ── String with only double-quote characters ─────────────────────────────

    @Test
    void stringOnlyDoubleQuotes_escapedCorrectly() {
        assertEquals("\"\"\"\"\"", CsvValueFormatter.format("\"\""),
                "Two double-quotes → each escaped as \"\" → total 6 quote chars");
    }

    // ── Very long string — no truncation ────────────────────────────────────

    @Test
    void veryLongString_noTruncation() {
        String longStr = "x".repeat(1_000_000);
        String result = CsvValueFormatter.format(longStr);
        assertEquals("\"" + longStr + "\"", result,
                "No length limit — 1M character string produces 1M+2 byte CSV field");
        assertTrue(result.length() > 1_000_000);
    }

    // ── byte[] with large data ───────────────────────────────────────────────

    @Test
    void largeByteArray_base64EncodingSize() {
        byte[] data = new byte[3_000_000]; // 3MB
        String result = CsvValueFormatter.format(data);
        // Base64 encoding: ceil(3_000_000 / 3) * 4 = 4_000_000 chars + 2 quotes
        assertTrue(result.length() > 4_000_000,
                "3MB byte[] produces >4MB Base64 CSV field");
    }

    // ── Negative numbers ─────────────────────────────────────────────────────

    @Test
    void negativeInteger_quotedCorrectly() {
        assertEquals("\"-42\"", CsvValueFormatter.format(-42));
    }

    @Test
    void negativeLong_quotedCorrectly() {
        assertEquals("\"-9999999999\"", CsvValueFormatter.format(-9_999_999_999L));
    }

    // ── Character type ───────────────────────────────────────────────────────

    @Test
    void charValue_fallsThroughToToString() {
        // JDBC sometimes returns Character; falls through to toString()
        assertEquals("\"A\"", CsvValueFormatter.format('A'));
    }

    // ── Empty string vs NULL ─────────────────────────────────────────────────

    @Test
    void emptyString_vsNull_distinctOutput() {
        String emptyResult = CsvValueFormatter.format("");
        String nullResult = CsvValueFormatter.format(null);
        assertNotEquals(emptyResult, nullResult,
                "Empty string and NULL must produce distinct CSV output");
        assertEquals("\"\"", emptyResult);
        assertEquals("\\N", nullResult);
    }

    // ── String containing comma — no special escaping beyond quotes ──────────

    @Test
    void stringWithComma_noCommaEscaping() {
        // RFC 4180 says comma inside quoted field doesn't need escaping
        assertEquals("\"a,b,c\"", CsvValueFormatter.format("a,b,c"));
    }

    // ── BigDecimal: scientific notation toString ─────────────────────────────

    @Test
    void bigDecimal_scientificNotation_expandedToPlainDecimal() {
        // new BigDecimal("1E+10") → toPlainString() = "10000000000"
        java.math.BigDecimal bd = new java.math.BigDecimal("1E+10");
        String result = CsvValueFormatter.format(bd);
        assertEquals("\"10000000000\"", result,
                "BigDecimal with scientific notation is expanded to plain decimal");
    }

    @Test
    void bigDecimal_veryLargeScale_plainDecimal() {
        // new BigDecimal("0.0000000001") → toString() = "0.0000000001" (plain)
        java.math.BigDecimal bd = new java.math.BigDecimal("0.0000000001");
        String result = CsvValueFormatter.format(bd);
        assertEquals("\"0.0000000001\"", result);
    }

    // ── Float NaN / Infinity (not Double) ───────────────────────────────────

    @Test
    void floatNaN_fallsThroughToToString() {
        // Float is NOT instanceof Double → falls through to toString()
        assertEquals("\"NaN\"", CsvValueFormatter.format(Float.NaN),
                "Float.NaN falls through to toString() — same invalid SQL output as Double.NaN");
    }

    @Test
    void floatInfinity_fallsThroughToToString() {
        assertEquals("\"Infinity\"", CsvValueFormatter.format(Float.POSITIVE_INFINITY));
    }

    // ── java.time types from JDBC 4.2+ ──────────────────────────────────────

    @Test
    void localDate_fallsThroughToToString_isoFormat() {
        // JDBC 4.2+ getObject() may return java.time.LocalDate
        // Falls through to toString() → "2024-01-15"
        // This is correct format, but it's NOT handled by the Date branch
        java.time.LocalDate ld = java.time.LocalDate.of(2024, 1, 15);
        String result = CsvValueFormatter.format(ld);
        assertEquals("\"2024-01-15\"", result,
                "LocalDate falls through to toString() — produces correct format by accident");
    }

    @Test
    void localDateTime_usesSpaceSeparator() {
        // LocalDateTime is now handled explicitly — uses space separator
        java.time.LocalDateTime ldt = java.time.LocalDateTime.of(2024, 1, 15, 12, 30, 0);
        String result = CsvValueFormatter.format(ldt);
        assertEquals("\"2024-01-15 12:30:00.000\"", result,
                "LocalDateTime uses space separator with .000 millis");
    }

    @Test
    void localTime_fallsThroughToToString() {
        // JDBC 4.2+ getObject() may return java.time.LocalTime
        // toString() → "12:30:45" or "12:30:45.123456789"
        java.time.LocalTime lt = java.time.LocalTime.of(12, 30, 45);
        String result = CsvValueFormatter.format(lt);
        assertEquals("\"12:30:45\"", result,
                "LocalTime falls through to toString() — produces correct format by accident");
    }

    @Test
    void localTime_withNanos_fractionalStripped() {
        // LocalTime with nanos — fractional seconds now stripped
        java.time.LocalTime lt = java.time.LocalTime.of(12, 30, 45, 123456789);
        String result = CsvValueFormatter.format(lt);
        assertEquals("\"12:30:45\"", result,
                "LocalTime with nanos — fractional seconds stripped to HH:mm:ss");
    }

    // ── String with tab character ────────────────────────────────────────────

    @Test
    void stringWithTab_noEscaping() {
        assertEquals("\"col1\tcol2\"", CsvValueFormatter.format("col1\tcol2"),
                "Tab character is embedded as-is — may cause column alignment issues");
    }

    // ── String with Unicode surrogate pair ───────────────────────────────────

    @Test
    void stringWithSurrogatePair_preservedCorrectly() {
        // Emoji: U+1F600 (😀) = surrogate pair D83D DE00
        String emoji = "\uD83D\uDE00";
        String result = CsvValueFormatter.format(emoji);
        assertEquals("\"" + emoji + "\"", result,
                "Unicode surrogate pairs must be preserved in CSV");
    }

    // ── String with BOM character ────────────────────────────────────────────

    @Test
    void stringWithBOMCharacter_embeddedInOutput() {
        // U+FEFF (BOM) inside a field — not the file-level BOM
        String bomStr = "\uFEFFhello";
        String result = CsvValueFormatter.format(bomStr);
        assertEquals("\"\uFEFFhello\"", result,
                "BOM character inside field value is embedded — may confuse parsers");
    }

    // ── BigInteger (not BigDecimal) ──────────────────────────────────────────

    @Test
    void bigInteger_fallsThroughToToString() {
        java.math.BigInteger bi = new java.math.BigInteger("999999999999999999999");
        String result = CsvValueFormatter.format(bi);
        assertEquals("\"999999999999999999999\"", result,
                "BigInteger falls through to toString() — works but not explicitly handled");
    }

    // ── String with backslash ───────────────────────────────────────────────

    @Test
    void stringWithBackslash_notEscaped() {
        assertEquals("\"C:\\Users\\admin\"", CsvValueFormatter.format("C:\\Users\\admin"),
                "Backslash is not escaped in CSV — Dumpling reads it literally");
    }
}
