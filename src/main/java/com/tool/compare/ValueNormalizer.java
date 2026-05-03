package com.tool.compare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

final class ValueNormalizer {

    private ValueNormalizer() {}

    static String normalize(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (val instanceof String s) {
            // strip \r and trailing spaces (MySQL/TiDB trims CHAR trailing spaces)
            s = s.replace("\r\n", "\n").replace('\r', '\n');
            return s.replaceAll("\\s+$", "");
        }
        if (val instanceof java.sql.Timestamp ts) {
            long millis = ts.getTime();
            int nanos = ts.getNanos();
            int ms = nanos / 1_000_000;
            if (nanos % 1_000_000 >= 500_000) ms++;
            if (ms >= 1000) { millis++; ms -= 1000; }
            return new java.sql.Timestamp(millis).toString().replaceFirst("\\.\\d+$", "")
                    + "." + String.format("%03d", ms);
        }
        if (val instanceof LocalDateTime ldt) {
            // MySQL/TiDB Connector/J 8.x returns DATETIME as LocalDateTime
            java.sql.Timestamp ts = java.sql.Timestamp.valueOf(ldt);
            int nanos = ldt.getNano();
            int ms = nanos / 1_000_000;
            if (nanos % 1_000_000 >= 500_000) ms++;
            return ts.toString().replaceFirst("\\.\\d+$", "")
                    + "." + String.format("%03d", ms);
        }
        if (val instanceof LocalDate ld) {
            return ld.toString();
        }
        if (val instanceof LocalTime lt) {
            return lt.toString();
        }
        if (val instanceof OffsetTime ot) {
            return ot.toString();
        }
        if (val instanceof microsoft.sql.DateTimeOffset dto) {
            // SQL Server vendor type: normalize to local datetime (not UTC).
            // Use Instant+ZoneOffset.UTC to avoid Timestamp.toLocalDateTime()
            // which applies system timezone (would double-add TZ offset).
            java.sql.Timestamp utcTs = dto.getTimestamp();
            int offsetMinutes = dto.getMinutesOffset();
            int nanos = utcTs.getNanos();
            int ms = nanos / 1_000_000;
            if (nanos % 1_000_000 >= 500_000) ms++;
            LocalDateTime localLdt = utcTs.toInstant()
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .plusMinutes(offsetMinutes);
            if (ms >= 1000) { localLdt = localLdt.plusSeconds(1); ms -= 1000; }
            java.sql.Timestamp localTs = java.sql.Timestamp.valueOf(localLdt);
            return localTs.toString().replaceFirst("\\.\\d+$", "")
                    + "." + String.format("%03d", ms);
        }
        if (val instanceof java.time.OffsetDateTime odt) {
            // normalize to local datetime (not UTC) — TiDB DATETIME stores local time,
            // and the dump format drops the offset
            java.sql.Timestamp ts = java.sql.Timestamp.valueOf(odt.toLocalDateTime());
            int nanos = odt.getNano();
            int ms = nanos / 1_000_000;
            if (nanos % 1_000_000 >= 500_000) ms++;
            if (ms >= 1000) { ts.setTime(ts.getTime() + 1); ms -= 1000; }
            return ts.toString().replaceFirst("\\.\\d+$", "")
                    + "." + String.format("%03d", ms);
        }
        if (val instanceof java.sql.Time t) {
            return t.toString();
        }
        if (val instanceof java.sql.Date d) {
            return d.toString();
        }
        if (val instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (val instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return val.toString();
    }
}
