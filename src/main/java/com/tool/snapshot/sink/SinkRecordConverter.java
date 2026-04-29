package com.tool.snapshot.sink;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SinkRecordConverter {

    private final DataSource targetDs;
    /** Cache of "db.table" → column name → SQL type */
    private final Map<String, Map<String, Integer>> typeCache = new HashMap<>();

    public SinkRecordConverter(DataSource targetDs) {
        this.targetDs = targetDs;
    }

    public void bind(PreparedStatement ps, String dbName, String table,
                     Map<String, Object> row, List<String> fieldNames) throws SQLException {
        Map<String, Integer> colTypes = getColumnTypes(dbName, table);
        for (int i = 0; i < fieldNames.size(); i++) {
            String col = fieldNames.get(i);
            Object val = row.get(col);
            Integer sqlType = colTypes.get(col);
            bindAt(ps, i + 1, val, sqlType);
        }
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

    void bindAt(PreparedStatement ps, int idx, Object val, Integer sqlType) throws SQLException {
        if (val == null) {
            if (sqlType != null) ps.setNull(idx, sqlType);
            else ps.setNull(idx, Types.VARCHAR);
            return;
        }
        if (sqlType == null) {
            bindPlain(ps, idx, val);
            return;
        }
        switch (sqlType) {
            case Types.DATE -> {
                if (val instanceof Integer days) {
                    ps.setDate(idx, new java.sql.Date(days * 86400000L));
                } else if (val instanceof String s) {
                    ps.setDate(idx, java.sql.Date.valueOf(s));
                } else {
                    ps.setDate(idx, (java.sql.Date) val);
                }
            }
            case Types.TIME -> {
                if (val instanceof Long micros) {
                    ps.setTime(idx, new java.sql.Time(micros / 1000));
                } else if (val instanceof String s) {
                    ps.setTime(idx, java.sql.Time.valueOf(s));
                } else {
                    ps.setTime(idx, (java.sql.Time) val);
                }
            }
            case Types.TIMESTAMP -> {
                if (val instanceof Long nanos) {
                    long millis = nanos / 1_000_000;
                    int leftover = (int)(nanos % 1_000_000);
                    java.sql.Timestamp ts = new java.sql.Timestamp(millis);
                    ts.setNanos(leftover);
                    ps.setTimestamp(idx, ts);
                } else if (val instanceof String s) {
                    ps.setTimestamp(idx, java.sql.Timestamp.valueOf(s));
                } else {
                    ps.setTimestamp(idx, (java.sql.Timestamp) val);
                }
            }
            default -> bindPlain(ps, idx, val);
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
