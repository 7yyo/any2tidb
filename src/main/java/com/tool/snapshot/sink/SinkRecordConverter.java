package com.tool.snapshot.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.logging.Log;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class SinkRecordConverter {

    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private final DataSource targetDs;
    private static final Logger log = LoggerFactory.getLogger(SinkRecordConverter.class);
    /** Cache of "db.table" → column name → SQL type */
    private final Map<String, Map<String, Integer>> typeCache = new ConcurrentHashMap<>();

    public SinkRecordConverter(DataSource targetDs) {
        this.targetDs = targetDs;
    }

    public void bind(PreparedStatement ps, String dbName, String table,
                     Map<String, Object> row, List<String> fieldNames) throws SQLException {
        Map<String, Integer> colTypes = getColumnTypes(dbName, table);
        for (int i = 0; i < fieldNames.size(); i++) {
            String col = fieldNames.get(i);
            Object val = row.get(col);
            Integer sqlType = colTypes.get(col.toLowerCase());
            try {
                bindAt(ps, i + 1, val, sqlType);
            } catch (Exception e) {
                throw new SQLException("Bind failed for " + dbName + "." + table + "." + col
                        + " (sqlType=" + (sqlType != null ? sqlType : "null")
                        + ", valueType=" + (val != null ? val.getClass().getSimpleName() : "null")
                        + "): " + e.getMessage(), e);
            }
        }
    }

    /** Bind a single column value at the given index, looking up the SQL type from TiDB. */
    public void bindSingle(PreparedStatement ps, int idx, String dbName, String table,
                           String colName, Object value) throws SQLException {
        Map<String, Integer> colTypes = getColumnTypes(dbName, table);
        Integer sqlType = colTypes.get(colName.toLowerCase());
        bindAt(ps, idx, value, sqlType);
    }

    /**
     * Returns TiDB column definitions: column_name → type_string (e.g. "INT", "VARCHAR(200)", "DECIMAL(12,4)").
     * Includes "(PK)" suffix for primary key columns. Used for schema diff during DDL detection.
     */
    public Map<String, String> getColumnDefs(String dbName, String table) throws SQLException {
        Map<String, String> defs = new java.util.LinkedHashMap<>();
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY "
                   + "FROM INFORMATION_SCHEMA.COLUMNS "
                   + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                   + "ORDER BY ORDINAL_POSITION";
        try (Connection conn = targetDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String type = rs.getString(2);
                    String nullable = rs.getString(3);
                    String key = rs.getString(4);
                    StringBuilder sb = new StringBuilder(type.toUpperCase());
                    if ("NO".equals(nullable)) sb.append(" NOT NULL");
                    if ("PRI".equals(key)) sb.append(" PK");
                    defs.put(name, sb.toString());
                }
            }
        }
        return defs;
    }

    private Map<String, Integer> getColumnTypes(String dbName, String table) throws SQLException {
        String key = dbName + "." + table;
        Map<String, Integer> cached = typeCache.get(key);
        if (cached != null) return cached;

        Map<String, Integer> types = new HashMap<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (Connection conn = targetDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1).toLowerCase(),
                              sqlTypeFromName(rs.getString(2)));
                }
            }
        }
        if (types.isEmpty()) {
            Log.warn(log, "TiDB schema lookup returned 0 columns",
                    "table", dbName + "." + table,
                    "hint", "Table may not exist in TiDB or schema migration may have failed");
        }
        typeCache.put(key, types);
        return types;
    }

    private static int sqlTypeFromName(String name) {
        return switch (name.toUpperCase()) {
            case "INT", "INTEGER" -> Types.INTEGER;
            case "BIGINT" -> Types.BIGINT;
            case "SMALLINT" -> Types.SMALLINT;
            case "TINYINT" -> Types.TINYINT;
            case "FLOAT" -> Types.FLOAT;
            case "DOUBLE" -> Types.DOUBLE;
            case "DECIMAL", "NUMERIC" -> Types.DECIMAL;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "DATETIME", "TIMESTAMP" -> Types.TIMESTAMP;
            case "CHAR", "VARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT" -> Types.VARCHAR;
            case "BINARY", "VARBINARY", "BLOB" -> Types.BINARY;
            case "BIT" -> Types.BIT;
            default -> Types.VARCHAR;
        };
    }

    /**
     * Debezium serialises SQL Server {@code DATETIME2} as nanoseconds-since-epoch and
     * {@code DATETIME} / {@code SMALLDATETIME} as milliseconds-since-epoch.  A nanosecond
     * value for any real date is &gt;= 10^17; a millisecond value for any date before
     * ~5141 is &lt; 10^14.  Use 10^14 as the split point to auto-detect the unit.
     */
    /**
     * SQL Server DATETIME max (~9999-12-31) ≈ 2.5×10¹⁴ ms.
     * SQL Server DATETIME2 for recent dates ≈ 1.8×10¹⁸ ns.
     * Any value whose absolute magnitude is ≥ 10¹⁵ must be nanosecond-encoded.
     */
    private static final long NS_MS_THRESHOLD = 1_000_000_000_000_000L; // 10^15

    /** TiDB DATE / DATETIME range: 1000-01-01 to 9999-12-31 (SQL Server goes back to 0001-01-01). */
    private static final java.sql.Date TIDB_MIN_DATE = java.sql.Date.valueOf("1000-01-01");
    private static final java.sql.Timestamp TIDB_MIN_DATETIME = java.sql.Timestamp.valueOf("1000-01-01 00:00:00");
    private static final java.sql.Timestamp TIDB_MAX_DATETIME = java.sql.Timestamp.valueOf("9999-12-31 23:59:59");

    public void bindAt(PreparedStatement ps, int idx, Object val, Integer sqlType) throws SQLException {
        if (val == null) {
            if (sqlType != null) ps.setNull(idx, sqlType);
            else ps.setNull(idx, Types.VARCHAR);
            return;
        }
        if (sqlType == null) {
            bindInferred(ps, idx, val);
            return;
        }
        switch (sqlType) {
            case Types.DATE -> {
                java.sql.Date d = toDate(val);
                if (d == null) {
                    ps.setNull(idx, Types.DATE);
                } else if (d.before(TIDB_MIN_DATE)) {
                    ps.setDate(idx, TIDB_MIN_DATE);
                } else {
                    ps.setDate(idx, d);
                }
            }
            case Types.TIME -> {
                // Debezium serializes SQL Server TIME as INT64. Auto-detect unit:
                //   nanos > 86_400_000_000L   (24h in µs = 8.64×10^10)
                //   micros > 86_400_000L       (24h in ms = 8.64×10^7)
                //   millis <= 86_400_000L
                long nanos;
                if (val instanceof Long raw) {
                    if (raw > 86_400_000_000L)      nanos = raw;
                    else if (raw > 86_400_000L)     nanos = raw * 1_000L;
                    else                            nanos = raw * 1_000_000L;
                } else if (val instanceof Integer raw) {
                    nanos = raw * 1_000_000L;
                } else if (val instanceof String s) {
                    try {
                        long raw = Long.parseLong(s);
                        if (raw > 86_400_000_000L)      nanos = raw;
                        else if (raw > 86_400_000L)     nanos = raw * 1_000L;
                        else                            nanos = raw * 1_000_000L;
                    } catch (NumberFormatException nfe) {
                        ps.setString(idx, s);
                        break;
                    }
                } else {
                    ps.setTime(idx, (java.sql.Time) val, UTC);
                    break;
                }
                // Truncate to microseconds (TiDB TIME supports max 6 fractional digits)
                // Use setString to avoid java.sql.Time precision loss
                long micros = nanos / 1000;
                long secs = micros / 1_000_000;
                String timeStr = String.format("%02d:%02d:%02d.%06d",
                        secs / 3600, (secs % 3600) / 60, secs % 60, micros % 1_000_000);
                ps.setString(idx, timeStr);
            }
            case Types.TIMESTAMP -> {
                java.sql.Timestamp ts = toTimestamp(val);
                if (ts == null) {
                    ps.setNull(idx, Types.TIMESTAMP);
                } else if (ts.before(TIDB_MIN_DATETIME)) {
                    ps.setTimestamp(idx, TIDB_MIN_DATETIME, UTC);
                } else if (ts.after(TIDB_MAX_DATETIME)) {
                    ps.setTimestamp(idx, TIDB_MAX_DATETIME, UTC);
                } else {
                    ps.setTimestamp(idx, ts, UTC);
                }
            }
            // Debezium sends rowversion/timestamp as base64 string; decode for numeric targets
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                if (val instanceof String s) {
                    byte[] decoded = Base64.getDecoder().decode(s);
                    long v = 0;
                    for (byte b : decoded) {
                        v = (v << 8) | (b & 0xFF);
                    }
                    ps.setLong(idx, v);
                } else {
                    bindPlain(ps, idx, val);
                }
            }
            case Types.BINARY, Types.VARBINARY -> {
                if (val instanceof String s) {
                    ps.setBytes(idx, Base64.getDecoder().decode(s));
                } else {
                    bindPlain(ps, idx, val);
                }
            }
            default -> bindPlain(ps, idx, val);
        }
    }

    private static java.sql.Date toDate(Object val) {
        if (val instanceof Integer days) {
            return java.sql.Date.valueOf(java.time.LocalDate.ofEpochDay(days));
        } else if (val instanceof Long days) {
            return java.sql.Date.valueOf(java.time.LocalDate.ofEpochDay(days));
        } else if (val instanceof String s) {
            try {
                int days = Integer.parseInt(s);
                return java.sql.Date.valueOf(java.time.LocalDate.ofEpochDay(days));
            } catch (NumberFormatException nfe) {
                return java.sql.Date.valueOf(s);
            }
        } else if (val instanceof java.sql.Date d) {
            return d;
        }
        return null;
    }

    private static java.sql.Timestamp toTimestamp(Object val) {
        if (val instanceof Long v) {
            boolean isNanos = v >= NS_MS_THRESHOLD || v <= -NS_MS_THRESHOLD;
            long millis = isNanos ? v / 1_000_000 : v;
            int leftover = isNanos ? (int)Math.floorMod(v, 1_000_000_000) : 0;
            leftover = (leftover / 1000) * 1000; // truncate to µs (TiDB supports 6 digits)
            java.sql.Timestamp ts = new java.sql.Timestamp(millis);
            if (leftover > 0) ts.setNanos(leftover);
            return ts;
        } else if (val instanceof String s) {
            try {
                long v = Long.parseLong(s);
                long millis = v >= NS_MS_THRESHOLD ? v / 1_000_000 : v;
                int leftover = v >= NS_MS_THRESHOLD ? (int)Math.floorMod(v, 1_000_000_000) : 0;
                leftover = (leftover / 1000) * 1000;
                java.sql.Timestamp ts = new java.sql.Timestamp(millis);
                if (leftover > 0) ts.setNanos(leftover);
                return ts;
            } catch (NumberFormatException nfe) {
                try {
                    return java.sql.Timestamp.from(Instant.parse(s));
                } catch (Exception e2) {
                    return java.sql.Timestamp.valueOf(s);
                }
            }
        } else if (val instanceof java.sql.Timestamp t) {
            return t;
        } else if (val instanceof java.util.Date d) {
            return new java.sql.Timestamp(d.getTime());
        }
        return null;
    }

    /**
     * Fallback when TiDB column type is unknown: guess from Java value type.
     * Only activates for Long values that look like epoch timestamps.
     */
    private void bindInferred(PreparedStatement ps, int idx, Object val) throws SQLException {
        if (val instanceof Long l && l >= NS_MS_THRESHOLD) {
            // Probably a DATETIME2 epoch-nanos value where target type wasn't resolved
            long millis = l / 1_000_000;
            int leftover = (int)(l % 1_000_000_000);
            leftover = (leftover / 1000) * 1000;
            java.sql.Timestamp ts = new java.sql.Timestamp(millis);
            if (leftover > 0) ts.setNanos(leftover);
            ps.setTimestamp(idx, ts);
        } else {
            bindPlain(ps, idx, val);
        }
    }

    private void bindPlain(PreparedStatement ps, int idx, Object val) throws SQLException {
        if (val instanceof Integer i) {
            ps.setInt(idx, i);
        } else if (val instanceof Short s) {
            ps.setInt(idx, s.intValue());
        } else if (val instanceof Long l) {
            ps.setLong(idx, l);
        } else if (val instanceof Double d) {
            ps.setDouble(idx, d);
        } else if (val instanceof Float f) {
            ps.setDouble(idx, f.doubleValue());
        } else if (val instanceof BigDecimal bd) {
            ps.setBigDecimal(idx, bd);
        } else if (val instanceof BigInteger bi) {
            ps.setBigDecimal(idx, new BigDecimal(bi));
        } else if (val instanceof Boolean b) {
            ps.setBoolean(idx, b);
        } else if (val instanceof byte[] bytes) {
            ps.setBytes(idx, bytes);
        } else {
            ps.setString(idx, val.toString());
        }
    }
}
