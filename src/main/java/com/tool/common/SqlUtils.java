package com.tool.common;

/** SQL identifier escaping helpers for SQL Server and TiDB/MySQL. */
public final class SqlUtils {
    private SqlUtils() {}

    /** Escape SQL Server bracket-quoted identifiers: {@code ]} → {@code ]]} */
    public static String escapeBracket(String s) {
        return s.replace("]", "]]");
    }

    /** Escape TiDB/MySQL backtick-quoted identifiers: {@code `} → {@code ``} */
    public static String escapeBacktick(String s) {
        return s.replace("`", "``");
    }
}
