// src/test/java/com/tool/converter/TypeMapperTest.java
package com.tool.converter;

import com.tool.model.ColumnSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeMapperTest {

    private TypeMapper mapper = new TypeMapper();

    private ColumnSchema col(String type) {
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType(type);
        return c;
    }

    private ColumnSchema col(String type, int maxLength) {
        ColumnSchema c = col(type);
        c.setMaxLength(maxLength);
        return c;
    }

    private ColumnSchema col(String type, int precision, int scale) {
        ColumnSchema c = col(type);
        c.setPrecision(precision);
        c.setScale(scale);
        return c;
    }

    @Test void int_mapsToInt() { assertEquals("INT", mapper.mapType(col("int")).tidbType()); }
    @Test void bigint_mapsToBigint() { assertEquals("BIGINT", mapper.mapType(col("bigint")).tidbType()); }
    @Test void smallint_mapsToSmallint() { assertEquals("SMALLINT", mapper.mapType(col("smallint")).tidbType()); }
    @Test void tinyint_mapsToTinyintUnsigned() { assertEquals("TINYINT UNSIGNED", mapper.mapType(col("tinyint")).tidbType()); }
    @Test void bit_mapsToTinyint1() { assertEquals("TINYINT(1)", mapper.mapType(col("bit")).tidbType()); }
    @Test void decimal_mapsWithPrecision() { assertEquals("DECIMAL(10,2)", mapper.mapType(col("decimal", 10, 2)).tidbType()); }
    @Test void money_mapsToDecimal() { assertEquals("DECIMAL(19,4)", mapper.mapType(col("money")).tidbType()); assertTrue(mapper.mapType(col("money")).hasWarning()); }
    @Test void float_mapsToDouble() { assertEquals("DOUBLE", mapper.mapType(col("float")).tidbType()); }
    @Test void real_mapsToFloat() { assertEquals("FLOAT", mapper.mapType(col("real")).tidbType()); }
    @Test void date_mapsToDate() { assertEquals("DATE", mapper.mapType(col("date")).tidbType()); }
    @Test void datetime_mapsToDatetime() { assertEquals("DATETIME", mapper.mapType(col("datetime")).tidbType()); }
    @Test void datetime2_mapsWithWarning() { assertTrue(mapper.mapType(col("datetime2")).hasWarning()); }
    @Test void datetimeoffset_mapsWithWarning() { assertTrue(mapper.mapType(col("datetimeoffset")).hasWarning()); }
    @Test void varchar_mapsWithLength() { assertEquals("VARCHAR(100)", mapper.mapType(col("varchar", 100)).tidbType()); }
    @Test void varcharMax_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("varchar", -1)).tidbType()); assertTrue(mapper.mapType(col("varchar", -1)).hasWarning()); }
    // SQL Server stores nvarchar max_length in bytes (2 bytes per char).
    // nvarchar(50) → max_length=100 bytes → VARCHAR(50) CHARACTER SET utf8mb4
    @Test void nvarchar_mapsWithCharset() { assertEquals("VARCHAR(50) CHARACTER SET utf8mb4", mapper.mapType(col("nvarchar", 100)).tidbType()); }
    @Test void nvarcharMax_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("nvarchar", -1)).tidbType()); }
    @Test void text_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("text")).tidbType()); assertTrue(mapper.mapType(col("text")).hasWarning()); }
    @Test void image_mapsToLongblob() { assertEquals("LONGBLOB", mapper.mapType(col("image")).tidbType()); assertTrue(mapper.mapType(col("image")).hasWarning()); }
    @Test void uniqueidentifier_mapsToVarchar36() { assertEquals("VARCHAR(36)", mapper.mapType(col("uniqueidentifier")).tidbType()); }
    @Test void xml_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("xml")).tidbType()); }
    @Test void json_mapsToJson() { assertEquals("JSON", mapper.mapType(col("json")).tidbType()); }
    @Test void geometry_mapsToGeometry() { assertEquals("GEOMETRY", mapper.mapType(col("geometry")).tidbType()); }
    @Test void unknown_mapsToSkip() { assertTrue(mapper.mapType(col("someunknowntype")).skip()); }
}
