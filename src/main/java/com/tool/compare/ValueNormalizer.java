package com.tool.compare;

import java.math.BigDecimal;

final class ValueNormalizer {

    private ValueNormalizer() {}

    static String normalize(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (val instanceof String s) {
            return s.replace("\r\n", "\n").replace('\r', '\n');
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
