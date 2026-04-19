package com.tool.dump.writer;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converts a single Java value (as returned by {@link java.sql.ResultSet#getObject(int)})
 * into its CSV cell representation following Dumpling/Lightning conventions:
 * <ul>
 *   <li>NULL              → literal {@code \N} (no quotes)</li>
 *   <li>All other values  → double-quoted; internal {@code "} escaped as {@code ""}</li>
 *   <li>boolean/bit       → {@code "1"} / {@code "0"}</li>
 *   <li>Timestamp         → {@code "yyyy-MM-dd HH:mm:ss.SSS"}</li>
 *   <li>Date              → {@code "yyyy-MM-dd"}</li>
 *   <li>Time              → {@code "HH:mm:ss"}</li>
 *   <li>byte[]            → Base64-encoded string, quoted</li>
 * </ul>
 */
public final class CsvValueFormatter {

    private CsvValueFormatter() {}

    /** Dumpling NULL literal — no surrounding quotes. */
    public static final String NULL_LITERAL = "\\N";

    public static String format(Object value) {
        if (value == null) return NULL_LITERAL;

        String raw;

        if (value instanceof Boolean b) {
            raw = b ? "1" : "0";
        } else if (value instanceof byte[] bytes) {
            raw = Base64.getEncoder().encodeToString(bytes);
        } else if (value instanceof Timestamp ts) {
            // Format: yyyy-MM-dd HH:mm:ss.SSS (always 3 fractional digits)
            long millis = ts.getTime() % 1000;
            if (millis < 0) millis += 1000;
            raw = String.format("%s.%03d",
                    ts.toLocalDateTime().withNano(0)
                      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    millis);
        } else if (value instanceof Date d) {
            raw = d.toLocalDate().toString();                    // yyyy-MM-dd
        } else if (value instanceof Time t) {
            raw = t.toLocalTime().toString();                    // HH:mm:ss[.nnn]
        } else {
            raw = value.toString();
        }

        // Wrap in double quotes; escape internal double quotes as ""
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
