package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;
import org.springframework.stereotype.Component;

@Component
public class TypeMapper {

    public record MappedType(String tidbType, boolean hasWarning, boolean skip, String warningMessage) {
        public static MappedType of(String type) { return new MappedType(type, false, false, null); }
        public static MappedType warn(String type, String msg) { return new MappedType(type, true, false, msg); }
        public static MappedType skip(String msg) { return new MappedType(null, true, true, msg); }
    }

    public MappedType mapType(ColumnSchema col) {
        String type = col.getSourceType().toLowerCase().trim();
        Integer len = col.getMaxLength();
        Integer prec = col.getPrecision();
        Integer scale = col.getScale();

        return switch (type) {
            case "int", "integer" -> MappedType.of("INT");
            case "bigint" -> MappedType.of("BIGINT");
            case "smallint" -> MappedType.of("SMALLINT");
            case "tinyint" -> MappedType.of("TINYINT UNSIGNED");
            case "bit" -> MappedType.of("TINYINT(1)");
            case "decimal", "numeric" -> {
                int p = (prec != null && prec > 0) ? prec : 18;
                int s = (scale != null) ? scale : 0;
                yield MappedType.of("DECIMAL(" + p + "," + s + ")");
            }
            case "money" -> MappedType.warn("DECIMAL(19,4)", "MONEY converted to DECIMAL(19,4)");
            case "smallmoney" -> MappedType.warn("DECIMAL(10,4)", "SMALLMONEY converted to DECIMAL(10,4)");
            case "float" -> {
                // Source float(p): p <= 24 = single-precision (4 bytes), p > 24 = double-precision (8 bytes)
                // TiDB FLOAT = single-precision, DOUBLE = double-precision
                int p = (prec != null && prec > 0) ? prec : 53; // default precision is 53 (double)
                if (p <= 24) {
                    yield MappedType.warn("FLOAT", "FLOAT(" + p + ") is single-precision; stored as TiDB FLOAT (4 bytes) — precision may differ from source");
                } else {
                    yield MappedType.of("DOUBLE");
                }
            }
            case "real" -> MappedType.of("FLOAT");
            case "date" -> MappedType.of("DATE");
            case "time" -> {
                int fsp = (scale != null && scale > 0) ? Math.min(scale, 6) : 0;
                yield fsp > 0 ? MappedType.of("TIME(" + fsp + ")") : MappedType.of("TIME");
            }
            case "datetime", "smalldatetime" -> {
                int fsp = (scale != null && scale > 0) ? Math.min(scale, 6) : 0;
                yield fsp > 0 ? MappedType.of("DATETIME(" + fsp + ")") : MappedType.of("DATETIME");
            }
            case "datetime2" -> {
                int fsp = (scale != null && scale > 0) ? Math.min(scale, 6) : 6;
                boolean truncated = scale != null && scale > 6;
                String tidbType = fsp > 0 ? "DATETIME(" + fsp + ")" : "DATETIME";
                yield truncated
                        ? MappedType.warn(tidbType, "DATETIME2(" + scale + ") converted to " + tidbType + " — fractional seconds truncated from " + scale + " to 6 digits")
                        : MappedType.of(tidbType);
            }
            case "datetimeoffset" -> {
                int fsp = (scale != null && scale > 0) ? Math.min(scale, 6) : 0;
                String dt = fsp > 0 ? "DATETIME(" + fsp + ")" : "DATETIME";
                yield MappedType.warn(dt, "DATETIMEOFFSET converted to " + dt + ", timezone info lost");
            }
            case "char" -> MappedType.of("CHAR(" + (len != null && len > 0 ? len : 1) + ")");
            case "varchar" -> mapVarchar(len, false);
            case "text" -> MappedType.warn("LONGTEXT", "TEXT converted to LONGTEXT");
            case "nchar" -> {
                int charLen = (len != null && len > 0) ? len / 2 : 1;
                yield MappedType.of("CHAR(" + charLen + ") CHARACTER SET utf8mb4");
            }
            case "nvarchar" -> mapVarchar(len, true);
            case "ntext" -> MappedType.warn("LONGTEXT", "NTEXT converted to LONGTEXT");
            case "binary" -> MappedType.of("BINARY(" + (len != null && len > 0 ? len : 1) + ")");
            case "varbinary" -> {
                if (len != null && len == -1) {
                    yield MappedType.warn("LONGBLOB", "VARBINARY(MAX) converted to LONGBLOB");
                } else {
                    int vbLen = (len != null && len > 0) ? len : 1;
                    yield MappedType.of("VARBINARY(" + vbLen + ")");
                }
            }
            case "image" -> MappedType.warn("LONGBLOB", "IMAGE converted to LONGBLOB (deprecated type)");
            case "uniqueidentifier" -> MappedType.warn("VARCHAR(36)", "UNIQUEIDENTIFIER converted to VARCHAR(36)");
            case "rowversion", "timestamp" -> MappedType.warn("VARBINARY(8)", "ROWVERSION/TIMESTAMP (row version) converted to VARBINARY(8)");
            case "xml" -> MappedType.warn("LONGTEXT", "XML converted to LONGTEXT");
            case "json" -> MappedType.of("JSON");
            case "vector" -> MappedType.warn("VECTOR", "VECTOR requires TiDB v8.4+");
            case "hierarchyid" -> MappedType.warn("VARCHAR(4000)", "HIERARCHYID converted to VARCHAR(4000)");
            case "geography" -> MappedType.warn("LONGBLOB", "GEOGRAPHY converted to LONGBLOB (spatial operations not available in TiDB)");
            case "geometry" -> MappedType.warn("LONGBLOB", "GEOMETRY converted to LONGBLOB (spatial operations not available in TiDB)");
            case "sql_variant" -> MappedType.warn("LONGTEXT", "SQL_VARIANT converted to LONGTEXT");
            case "sysname" -> MappedType.of("VARCHAR(128)");
            case "cursor", "table" -> MappedType.skip(type.toUpperCase() + " is not applicable to table columns");
            default -> MappedType.skip("Unknown type '" + type + "' — column skipped");
        };
    }

    private MappedType mapVarchar(Integer len, boolean unicode) {
        String charset = unicode ? " CHARACTER SET utf8mb4" : "";
        if (len == null || len == -1) {
            return MappedType.warn("LONGTEXT", (unicode ? "NVARCHAR" : "VARCHAR") + "(MAX) converted to LONGTEXT");
        }
        // nvarchar stores 2 bytes per char; maxLength from sys.columns is in bytes
        int charLen = unicode ? len / 2 : len;
        return MappedType.of("VARCHAR(" + charLen + ")" + charset);
    }

    public String mapDefaultValue(String rawDefault) {
        if (rawDefault == null) return null;
        // Strip source wrapping parens: ((value)) or (value)
        String val = rawDefault.trim();
        while (val.startsWith("(") && val.endsWith(")")) {
            val = val.substring(1, val.length() - 1).trim();
        }
        return switch (val.toUpperCase()) {
            case "GETDATE()" -> "CURRENT_TIMESTAMP";
            case "GETUTCDATE()" -> "UTC_TIMESTAMP()";
            case "NEWID()" -> "UUID()";
            case "NEWSEQUENTIALID()" -> "UUID()";
            case "SYSDATETIME()" -> "CURRENT_TIMESTAMP";
            case "SYSDATETIMEOFFSET()" -> "CURRENT_TIMESTAMP";
            default -> val;
        };
    }
}
