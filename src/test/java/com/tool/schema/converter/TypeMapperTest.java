// src/test/java/com/tool/schema/converter/TypeMapperTest.java
package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;
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
    @Test void float_defaultPrecision_noWarning() { assertFalse(mapper.mapType(col("float")).hasWarning()); }

    @Test void float_singlePrecision_mapsToFloatWithWarning() {
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType("float");
        c.setPrecision(24);
        TypeMapper.MappedType r = mapper.mapType(c);
        assertEquals("FLOAT", r.tidbType());
        assertTrue(r.hasWarning());
        assertTrue(r.warningMessage().contains("single-precision"));
    }

    @Test void float_doublePrecision_mapsToDouble() {
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType("float");
        c.setPrecision(53);
        TypeMapper.MappedType r = mapper.mapType(c);
        assertEquals("DOUBLE", r.tidbType());
        assertFalse(r.hasWarning());
    }

    @Test void float_precisionBoundary_25_mapsToDouble() {
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType("float");
        c.setPrecision(25);
        TypeMapper.MappedType r = mapper.mapType(c);
        assertEquals("DOUBLE", r.tidbType());
        assertFalse(r.hasWarning());
    }
    @Test void real_mapsToFloat() { assertEquals("FLOAT", mapper.mapType(col("real")).tidbType()); }
    @Test void date_mapsToDate() { assertEquals("DATE", mapper.mapType(col("date")).tidbType()); }
    @Test void datetime_mapsToDatetime() { assertEquals("DATETIME", mapper.mapType(col("datetime")).tidbType()); }
    @Test void datetime2_precisionWithin6_mapsExact() {
        TypeMapper.MappedType r = mapper.mapType(col("datetime2", 0, 3));
        assertEquals("DATETIME(3)", r.tidbType());
        assertFalse(r.hasWarning());
    }
    @Test void datetime2_precision7_truncatedTo6WithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("datetime2", 0, 7));
        assertEquals("DATETIME(6)", r.tidbType());
        assertTrue(r.hasWarning());
        assertTrue(r.warningMessage().contains("truncated"));
    }
    @Test void datetime2_noScale_defaultsTo6() {
        TypeMapper.MappedType r = mapper.mapType(col("datetime2"));
        assertEquals("DATETIME(6)", r.tidbType());
        assertFalse(r.hasWarning());
    }
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
    @Test void geometry_mapsToLongblobWithWarning() { TypeMapper.MappedType r = mapper.mapType(col("geometry")); assertEquals("LONGBLOB", r.tidbType()); assertTrue(r.hasWarning()); }
    @Test void unknown_mapsToSkip() { assertTrue(mapper.mapType(col("someunknowntype")).skip()); }

    // ── mapDefaultValue() ────────────────────────────────────────────────────

    @Test void mapDefaultValue_null_returnsNull() {
        assertNull(mapper.mapDefaultValue(null));
    }

    @Test void mapDefaultValue_getdate_returnsCURRENT_TIMESTAMP() {
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("(GETDATE())"));
    }

    @Test void mapDefaultValue_getdate_doubleWrapped() {
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("((GETDATE()))"));
    }

    @Test void mapDefaultValue_getutcdate_returnsUTC_TIMESTAMP() {
        assertEquals("UTC_TIMESTAMP()", mapper.mapDefaultValue("(GETUTCDATE())"));
    }

    @Test void mapDefaultValue_newid_returnsUUID() {
        assertEquals("UUID()", mapper.mapDefaultValue("(NEWID())"));
    }

    @Test void mapDefaultValue_newsequentialid_returnsUUID() {
        assertEquals("UUID()", mapper.mapDefaultValue("(NEWSEQUENTIALID())"));
    }

    @Test void mapDefaultValue_sysdatetime_returnsCURRENT_TIMESTAMP() {
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("(SYSDATETIME())"));
    }

    @Test void mapDefaultValue_literalInt_returnedAsIs() {
        assertEquals("0", mapper.mapDefaultValue("((0))"));
    }

    @Test void mapDefaultValue_literalString_returnedAsIs() {
        assertEquals("'CN'", mapper.mapDefaultValue("('CN')"));
    }

    @Test void mapDefaultValue_noParens_returnedAsIs() {
        assertEquals("42", mapper.mapDefaultValue("42"));
    }

    @Test void mapDefaultValue_caseInsensitive_getdate() {
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("(getdate())"));
    }

    // ── Missing type coverage ─────────────────────────────────────────────────

    @Test void integer_aliasForInt() {
        assertEquals("INT", mapper.mapType(col("integer")).tidbType());
    }

    @Test void numeric_mapsToDecimal() {
        assertEquals("DECIMAL(15,3)", mapper.mapType(col("numeric", 15, 3)).tidbType());
    }

    @Test void smallmoney_mapsToDecimalWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("smallmoney"));
        assertEquals("DECIMAL(10,4)", r.tidbType());
        assertTrue(r.hasWarning());
        assertTrue(r.warningMessage().contains("SMALLMONEY"));
    }

    @Test void smalldatetime_mapsToDatetime() {
        assertEquals("DATETIME", mapper.mapType(col("smalldatetime")).tidbType());
        assertFalse(mapper.mapType(col("smalldatetime")).hasWarning());
    }

    @Test void datetime2_scale0_defaultsToDatetime6() {
        // scale=0 is not >0, so fsp defaults to 6 → DATETIME(6)
        TypeMapper.MappedType r = mapper.mapType(col("datetime2", 0, 0));
        assertEquals("DATETIME(6)", r.tidbType());
        assertFalse(r.hasWarning());
    }

    @Test void datetime2_scaleExplicit0_viaColumnSchema_mapsToDatetime6() {
        // precision=27, scale=0 → fsp=(0>0)?min(0,6):6 = 6 → DATETIME(6)
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType("datetime2");
        c.setPrecision(27);
        c.setScale(0);
        TypeMapper.MappedType r = mapper.mapType(c);
        assertEquals("DATETIME(6)", r.tidbType());
        assertFalse(r.hasWarning());
    }

    @Test void nchar_mapsToCharWithCharset() {
        // nchar(10) → maxLength=20 bytes → CHAR(10) CHARACTER SET utf8mb4
        TypeMapper.MappedType r = mapper.mapType(col("nchar", 20));
        assertEquals("CHAR(10) CHARACTER SET utf8mb4", r.tidbType());
        assertFalse(r.hasWarning());
    }

    @Test void nchar_noLength_defaultsToChar1() {
        TypeMapper.MappedType r = mapper.mapType(col("nchar"));
        assertEquals("CHAR(1) CHARACTER SET utf8mb4", r.tidbType());
    }

    @Test void char_noLength_defaultsToChar1() {
        assertEquals("CHAR(1)", mapper.mapType(col("char")).tidbType());
    }

    @Test void char_withLength_mapsCorrectly() {
        assertEquals("CHAR(10)", mapper.mapType(col("char", 10)).tidbType());
    }

    @Test void binary_withLength_mapsToBinary() {
        assertEquals("BINARY(16)", mapper.mapType(col("binary", 16)).tidbType());
    }

    @Test void binary_noLength_defaultsToBinary1() {
        assertEquals("BINARY(1)", mapper.mapType(col("binary")).tidbType());
    }

    @Test void varbinary_withLength_mapsToVarbinary() {
        assertEquals("VARBINARY(512)", mapper.mapType(col("varbinary", 512)).tidbType());
        assertFalse(mapper.mapType(col("varbinary", 512)).hasWarning());
    }

    @Test void varbinaryMax_mapsToLongblobWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("varbinary", -1));
        assertEquals("LONGBLOB", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void varbinary_noLength_defaultsToVarbinary1() {
        // Bug 1: varbinary with null length must not produce VARBINARY(null)
        TypeMapper.MappedType r = mapper.mapType(col("varbinary"));
        assertEquals("VARBINARY(1)", r.tidbType());
        assertFalse(r.hasWarning());
    }

    @Test void rowversion_mapsWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("rowversion"));
        assertEquals("BIGINT UNSIGNED", r.tidbType());
        assertTrue(r.hasWarning());
        assertTrue(r.warningMessage().contains("ROWVERSION"));
    }

    @Test void timestamp_aliasRowversion_mapsWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("timestamp"));
        assertEquals("BIGINT UNSIGNED", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void hierarchyid_mapsToVarchar4000WithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("hierarchyid"));
        assertEquals("VARCHAR(4000)", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void geography_mapsToLongblobWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("geography"));
        assertEquals("LONGBLOB", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void sqlVariant_mapsToLongtextWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("sql_variant"));
        assertEquals("LONGTEXT", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void sysname_mapsToVarchar128() {
        assertEquals("VARCHAR(128)", mapper.mapType(col("sysname")).tidbType());
        assertFalse(mapper.mapType(col("sysname")).hasWarning());
    }

    @Test void cursor_skips() {
        TypeMapper.MappedType r = mapper.mapType(col("cursor"));
        assertTrue(r.skip());
        assertTrue(r.warningMessage().contains("CURSOR"));
    }

    @Test void table_skips() {
        TypeMapper.MappedType r = mapper.mapType(col("table"));
        assertTrue(r.skip());
        assertTrue(r.warningMessage().contains("TABLE"));
    }

    @Test void vector_mapsWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("vector"));
        assertEquals("VECTOR", r.tidbType());
        assertTrue(r.hasWarning());
        assertTrue(r.warningMessage().contains("TiDB v8.4+"));
    }

    @Test void ntext_mapsToLongtextWithWarning() {
        TypeMapper.MappedType r = mapper.mapType(col("ntext"));
        assertEquals("LONGTEXT", r.tidbType());
        assertTrue(r.hasWarning());
    }

    @Test void time_mapsToTime() {
        assertEquals("TIME", mapper.mapType(col("time")).tidbType());
    }
}
