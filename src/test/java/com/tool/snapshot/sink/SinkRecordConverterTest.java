package com.tool.snapshot.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SinkRecordConverterTest {

    private SinkRecordConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SinkRecordConverter();
    }

    @Test
    void integer_setInt() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "age", 25);
        verify(ps).setInt(1, 25);
    }

    @Test
    void long_setLong() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "id", 9999999999L);
        verify(ps).setLong(1, 9999999999L);
    }

    @Test
    void double_setDouble() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "price", 19.99);
        verify(ps).setDouble(1, 19.99);
    }

    @Test
    void string_setString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "name", "Alice");
        verify(ps).setString(1, "Alice");
    }

    @Test
    void boolean_setBoolean() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "active", true);
        verify(ps).setBoolean(1, true);
    }

    @Test
    void bigDecimal_setBigDecimal() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "amount", new BigDecimal("99.99"));
        verify(ps).setBigDecimal(1, new BigDecimal("99.99"));
    }

    @Test
    void multipleFields_correctIndex() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> row = Map.of("id", 1, "name", "Bob");
        converter.bind(ps, row, List.of("id", "name"));
        verify(ps).setInt(1, 1);
        verify(ps).setString(2, "Bob");
    }

    @Test
    void nullValue_setNull() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "name", null);
        verify(ps).setNull(1, Types.VARCHAR);
    }

    @Test
    void byteArray_setBytes() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "data", new byte[]{1, 2, 3});
        verify(ps).setBytes(1, new byte[]{1, 2, 3});
    }

    @Test
    void unsupportedType_fallsBackToString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        Object obj = new Object();
        converter.bind(ps, "bad", obj);
        verify(ps).setString(1, obj.toString());
    }
}
