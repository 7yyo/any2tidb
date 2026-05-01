package com.tool.logging;

import org.slf4j.Logger;

/**
 * Slf4j adapter that preserves the existing key=value log format.
 *
 * <pre>
 * Log.info(log, "sync database", "database", "HRDB", "tables", 5);
 * // → ["sync database"] [database=HRDB] [tables=5]
 * </pre>
 */
public final class Log {
    private Log() {}

    public static void debug(Logger log, String msg, Object... fields) {
        if (log.isDebugEnabled()) log.debug(format(msg, fields));
    }
    public static void info(Logger log, String msg, Object... fields) {
        if (log.isInfoEnabled()) log.info(format(msg, fields));
    }
    public static void warn(Logger log, String msg, Object... fields) {
        if (log.isWarnEnabled()) log.warn(format(msg, fields));
    }
    public static void error(Logger log, String msg, Object... fields) {
        if (log.isErrorEnabled()) log.error(format(msg, fields));
    }

    private static String format(String msg, Object... fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\"").append(msg).append("\"]");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            sb.append(" [").append(fields[i]).append("=");
            String v = String.valueOf(fields[i + 1]);
            if (v.contains(" ") || v.isEmpty())
                sb.append("\"").append(v).append("\"");
            else
                sb.append(v);
            sb.append("]");
        }
        return sb.toString();
    }
}
