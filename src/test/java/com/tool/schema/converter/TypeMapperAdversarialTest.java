package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for TypeMapper — targets edge cases that could produce
 * invalid TiDB DDL or silently lose data.
 */
class TypeMapperAdversarialTest {

    private TypeMapper mapper;
    private ColumnSchema col;

    @BeforeEach
    void setUp() {
        mapper = new TypeMapper();
        col = new ColumnSchema();
        col.setName("test_col");
        col.setSqlServerType("int");
        col.setNullable(true);
    }

    // ── NullPointerException on null sourceType ───────────────────────────────

    @Test
    void mapType_nullSourceType_throwsNPE() {
        col.setSqlServerType(null);
        assertThrows(NullPointerException.class, () -> mapper.mapType(col));
    }

    @Test
    void mapType_blankSourceType_mapsToUnknown() {
        col.setSqlServerType("  ");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip(), "blank source type should be skipped");
    }

    // ── DECIMAL edge cases ──────────────────────────────────────────────────

    @Test
    void decimal_scaleGreaterThanPrecision_producesInvalidDDL() {
        col.setSqlServerType("decimal");
        col.setPrecision(5);
        col.setScale(10);  // scale > precision is invalid in TiDB
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(5,10)", result.tidbType(),
                "DECIMAL(5,10) has scale > precision — should be caught before reaching TiDB");
        assertFalse(result.skip());
    }

    @Test
    void decimal_scaleExceeds30_producesInvalidDDL() {
        col.setSqlServerType("decimal");
        col.setPrecision(38);
        col.setScale(35);  // TiDB max scale is 30
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(38,35)", result.tidbType(),
                "DECIMAL(38,35) exceeds TiDB's max scale of 30");
        assertFalse(result.skip());
    }

    @Test
    void decimal_scaleZero_withPrecisionNull() {
        col.setSqlServerType("decimal");
        col.setPrecision(null);
        col.setScale(0);  // scale=0 becomes null in extractor, but here it's explicit 0
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(18,0)", result.tidbType());
    }

    @Test
    void decimal_allNull_defaultsTo18_0() {
        col.setSqlServerType("decimal");
        col.setPrecision(null);
        col.setScale(null);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(18,0)", result.tidbType());
    }

    // ── DATETIME2(0) silent precision upgrade ──────────────────────────────

    @Test
    void datetime2_scale0_silentUpgradeToDatetime6() {
        col.setSqlServerType("datetime2");
        col.setScale(0);  // explicit scale=0 → scale>0 is false → defaults to fsp=6
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DATETIME(6)", result.tidbType(),
                "DATETIME2(0) silently upgrades to DATETIME(6) — precision increased without warning");
        assertFalse(result.hasWarning(), "precision upgrade should NOT warn when scale=0");
    }

    @Test
    void datetime2_scale7_truncatedTo6() {
        col.setSqlServerType("datetime2");
        col.setScale(7);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DATETIME(6)", result.tidbType());
        assertTrue(result.hasWarning());
    }

    @Test
    void datetime2_scale3_preserved() {
        col.setSqlServerType("datetime2");
        col.setScale(3);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DATETIME(3)", result.tidbType());
        assertFalse(result.hasWarning());
    }

    // ── TIME fractional seconds ──────────────────────────────────────────────

    @Test
    void time_scale7_truncatedTo6() {
        col.setSqlServerType("time");
        col.setScale(7);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("TIME(6)", result.tidbType());
        assertTrue(result.hasWarning());
    }

    @Test
    void time_scale0_plainTime() {
        col.setSqlServerType("time");
        col.setScale(0);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("TIME", result.tidbType());
    }

    // ── VARCHAR(0) ──────────────────────────────────────────────────────

    @Test
    void varchar_length0_mapsToVarchar0() {
        col.setSqlServerType("varchar");
        col.setMaxLength(0);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(0)", result.tidbType(),
                "VARCHAR(0) is unusual — can only store empty strings");
    }

    @Test
    void nvarchar_length0_mapsToVarchar0() {
        col.setSqlServerType("nvarchar");
        col.setMaxLength(0);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(0) CHARACTER SET utf8mb4", result.tidbType());
    }

    // ── NCHAR/NVARCHAR odd byte length ─────────────────────────────────────

    @Test
    void nchar_oddByteLength_truncates() {
        col.setSqlServerType("nchar");
        col.setMaxLength(3);  // odd: 3/2 = 1
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("CHAR(1) CHARACTER SET utf8mb4", result.tidbType(),
                "NCHAR(3 bytes) should map to CHAR(1) — lost half a character");
    }

    @Test
    void nvarchar_oddByteLength_truncates() {
        col.setSqlServerType("nvarchar");
        col.setMaxLength(5);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(2) CHARACTER SET utf8mb4", result.tidbType());
    }

    // ── VARBINARY(0) silently becomes VARBINARY(1) ────────────────────────

    @Test
    void varbinary_length0_mapsToVarbinary1() {
        col.setSqlServerType("varbinary");
        col.setMaxLength(0);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARBINARY(1)", result.tidbType(),
                "VARBINARY(0) silently changed to VARBINARY(1)");
    }

    // ── FLOAT precision boundary ───────────────────────────────────────────

    @Test
    void float_precision24_mapsToFloat() {
        col.setSqlServerType("float");
        col.setPrecision(24);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("FLOAT", result.tidbType());
        assertTrue(result.hasWarning());
    }

    @Test
    void float_precision25_mapsToDouble() {
        col.setSqlServerType("float");
        col.setPrecision(25);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DOUBLE", result.tidbType());
        assertFalse(result.hasWarning());
    }

    @Test
    void float_precision1_mapsToFloat() {
        col.setSqlServerType("float");
        col.setPrecision(1);
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("FLOAT", result.tidbType());
        assertTrue(result.hasWarning());
    }

    // ── Default value conversions ──────────────────────────────────────────

    @Test
    void mapDefaultValue_getdate_convertsToCurrentTimestamp() {
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("GETDATE()"));
    }

    @Test
    void mapDefaultValue_getutcdate_convertsToUtcTimestamp() {
        assertEquals("UTC_TIMESTAMP()", mapper.mapDefaultValue("GETUTCDATE()"));
    }

    @Test
    void mapDefaultValue_nestedParens_stripped() {
        // Parens are stripped first, then function mapping is applied
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("((GETDATE()))"));
        assertEquals("CURRENT_TIMESTAMP", mapper.mapDefaultValue("(((GETDATE())))"));
    }

    @Test
    void mapDefaultValue_unknownFunction_preserved() {
        // Non-SQL-Server default should be passed through
        assertEquals("lower(UPPER(name))", mapper.mapDefaultValue("lower(UPPER(name))"));
    }

    @Test
    void mapDefaultValue_emptyString_preserved() {
        assertEquals("", mapper.mapDefaultValue(""));
    }

    @Test
    void mapDefaultValue_null_returnsNull() {
        assertNull(mapper.mapDefaultValue(null));
    }

    // ── Skip types ──────────────────────────────────────────────────────

    @Test
    void rowversion_skipped() {
        col.setSqlServerType("rowversion");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip());
    }

    @Test
    void timestamp_skipped() {
        col.setSqlServerType("timestamp");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip());
    }

    @Test
    void cursorType_skipped() {
        col.setSqlServerType("cursor");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip());
    }

    @Test
    void tableType_skipped() {
        col.setSqlServerType("table");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip());
    }

    @Test
    void unknownType_skipped() {
        col.setSqlServerType("sql_variant_fancy");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertTrue(result.skip());
    }

    // ── MONEY precision ──────────────────────────────────────────────────

    @Test
    void money_mapsToDecimal19_4() {
        col.setSqlServerType("money");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(19,4)", result.tidbType());
        assertTrue(result.hasWarning());
    }

    @Test
    void smallmoney_mapsToDecimal10_4() {
        col.setSqlServerType("smallmoney");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DECIMAL(10,4)", result.tidbType());
        assertTrue(result.hasWarning());
    }

    // ── Special type mappings ────────────────────────────────────────────

    @Test
    void datetimeoffset_warnsTimezoneLost() {
        col.setSqlServerType("datetimeoffset");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("DATETIME", result.tidbType());
        assertTrue(result.hasWarning());
        assertTrue(result.warningMessage().contains("timezone"));
    }

    @Test
    void uniqueidentifier_mapsToVarchar36() {
        col.setSqlServerType("uniqueidentifier");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(36)", result.tidbType());
    }

    @Test
    void xml_mapsToLongtext() {
        col.setSqlServerType("xml");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("LONGTEXT", result.tidbType());
    }

    @Test
    void hierarchyid_mapsToVarchar4000() {
        col.setSqlServerType("hierarchyid");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(4000)", result.tidbType());
    }

    @Test
    void sysname_mapsToVarchar128() {
        col.setSqlServerType("sysname");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("VARCHAR(128)", result.tidbType());
    }

    @Test
    void json_mapsToJson() {
        col.setSqlServerType("json");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("JSON", result.tidbType());
    }

    @Test
    void bit_mapsToTinyint1() {
        col.setSqlServerType("bit");
        TypeMapper.MappedType result = mapper.mapType(col);
        assertEquals("TINYINT(1)", result.tidbType());
    }
}
