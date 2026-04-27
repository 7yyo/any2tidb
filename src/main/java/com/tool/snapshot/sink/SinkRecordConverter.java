package com.tool.snapshot.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

public class SinkRecordConverter {

    private static final Logger log = LoggerFactory.getLogger(SinkRecordConverter.class);

    public void bind(PreparedStatement ps, String fieldName, Object value) throws SQLException {
        bindAt(ps, 1, value);
    }

    public void bind(PreparedStatement ps, Map<String, Object> row, List<String> fieldNames) throws SQLException {
        for (int i = 0; i < fieldNames.size(); i++) {
            Object val = row.get(fieldNames.get(i));
            bindAt(ps, i + 1, val);
        }
    }

    private void bindAt(PreparedStatement ps, int idx, Object val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.VARCHAR);
            return;
        }
        if (val instanceof Integer) {
            ps.setInt(idx, (Integer) val);
        } else if (val instanceof Short) {
            ps.setInt(idx, ((Short) val).intValue());
        } else if (val instanceof Long) {
            ps.setLong(idx, (Long) val);
        } else if (val instanceof Double) {
            ps.setDouble(idx, (Double) val);
        } else if (val instanceof Float) {
            ps.setDouble(idx, ((Float) val).doubleValue());
        } else if (val instanceof BigDecimal) {
            ps.setBigDecimal(idx, (BigDecimal) val);
        } else if (val instanceof BigInteger) {
            ps.setBigDecimal(idx, new BigDecimal((BigInteger) val));
        } else if (val instanceof Boolean) {
            ps.setBoolean(idx, (Boolean) val);
        } else if (val instanceof byte[]) {
            ps.setBytes(idx, (byte[]) val);
        } else if (val instanceof String) {
            ps.setString(idx, val.toString());
        } else {
            ps.setString(idx, val.toString());
        }
    }
}
