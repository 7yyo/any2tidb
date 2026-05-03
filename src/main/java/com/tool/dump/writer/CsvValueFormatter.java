package com.tool.dump.writer;

import microsoft.sql.DateTimeOffset;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 *   <li>LocalDateTime     → {@code "yyyy-MM-dd HH:mm:ss.SSS"}</li>
 *   <li>LocalDate         → {@code "yyyy-MM-dd"}</li>
 *   <li>LocalTime         → {@code "HH:mm:ss"}</li>
 *   <li>BigDecimal        → plain decimal string (no scientific notation)</li>
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
            // Round nanos to milliseconds instead of truncating
            long nanos = ts.getNanos();
            long millis = (nanos + 500_000) / 1_000_000; // round half-up
            if (millis >= 1000) {
                // Rounding overflow: add 1 second
                ts = new Timestamp(ts.getTime() + 1000);
                millis = 0;
            }
            raw = String.format("%s.%03d",
                    ts.toLocalDateTime().withNano(0)
                      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    millis);
        } else if (value instanceof Date d) {
            raw = d.toLocalDate().toString();                    // yyyy-MM-dd
        } else if (value instanceof Time t) {
            // Strip fractional seconds — Dumpling expects HH:mm:ss only
            raw = t.toLocalTime().withNano(0).toString();       // HH:mm:ss
        } else if (value instanceof LocalDateTime ldt) {
            // JDBC 4.2+ may return LocalDateTime; use space separator, not 'T'
            raw = String.format("%s.%03d",
                    ldt.withNano(0)
                      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    (ldt.getNano() + 500_000) / 1_000_000);
        } else if (value instanceof LocalDate ld) {
            raw = ld.toString();                                 // yyyy-MM-dd
        } else if (value instanceof LocalTime lt) {
            // Strip fractional seconds — Dumpling expects HH:mm:ss only
            raw = lt.withNano(0).toString();                     // HH:mm:ss
        } else if (value instanceof DateTimeOffset dto) {
            // datetimeoffset (SQL Server vendor type): write local datetime
            // (no offset — TiDB DATETIME has no tz).
            // dto.getTimestamp() is UTC; add offset to get local time.
            // Use Instant+ZoneOffset.UTC to avoid Timestamp.toLocalDateTime()
            // which applies system timezone (would double-add TZ offset).
            Timestamp utcTs = dto.getTimestamp();
            int offsetMinutes = dto.getMinutesOffset();
            int nanos = utcTs.getNanos();
            int ms = (nanos + 500_000) / 1_000_000;
            LocalDateTime localLdt = utcTs.toInstant()
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .plusMinutes(offsetMinutes);
            if (ms >= 1000) { localLdt = localLdt.plusSeconds(1); ms -= 1000; }
            raw = String.format("%s.%03d",
                    localLdt.withNano(0)
                          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    ms);
        } else if (value instanceof OffsetDateTime odt) {
            // datetimeoffset (JDBC 4.2+ type): write local datetime, not UTC
            raw = String.format("%s.%03d",
                    odt.toLocalDateTime().withNano(0)
                       .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    (odt.getNano() + 500_000) / 1_000_000);
        } else if (value instanceof BigDecimal bd) {
            // Use plain notation — avoid scientific notation like 1E+10
            raw = bd.toPlainString();
        } else {
            raw = value.toString();
        }

        // Escape backslash for LOAD DATA default ESCAPED BY '\', then quotes for enclosure
        raw = raw.replace("\\", "\\\\");
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
