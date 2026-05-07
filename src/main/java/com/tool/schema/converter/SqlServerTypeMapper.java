package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;
import org.springframework.stereotype.Component;

@Component
public class SqlServerTypeMapper implements TypeMapper {

    @Override
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
            case "money" -> MappedType.warn("DECIMAL(19,4)", "");
            case "smallmoney" -> MappedType.warn("DECIMAL(10,4)", "");
            case "float" -> {
                int p = (prec != null && prec > 0) ? prec : 53;
                if (p <= 24) {
                    yield MappedType.warn("FLOAT", "single-precision (4 bytes), may differ from source");
                } else {
                    yield MappedType.of("DOUBLE");
                }
            }
            case "real" -> MappedType.of("FLOAT");
            case "date" -> MappedType.of("DATE");
            case "time" -> {
                int fsp = (scale != null) ? Math.min(scale, 6) : 0;
                yield fsp > 0 ? MappedType.of("TIME(" + fsp + ")") : MappedType.of("TIME");
            }
            case "datetime", "smalldatetime" -> {
                int fsp = (scale != null) ? Math.min(scale, 6) : 0;
                yield fsp > 0 ? MappedType.of("DATETIME(" + fsp + ")") : MappedType.of("DATETIME");
            }
            case "datetime2" -> {
                int fsp = (scale != null) ? Math.min(scale, 6) : 6;
                boolean truncated = scale != null && scale > 6;
                String tidbType = fsp > 0 ? "DATETIME(" + fsp + ")" : "DATETIME";
                yield truncated
                        ? MappedType.warn(tidbType, "fractional seconds truncated " + scale + "→6")
                        : MappedType.of(tidbType);
            }
            case "datetimeoffset" -> {
                int fsp = (scale != null && scale > 0) ? Math.min(scale, 6) : 0;
                String dt = fsp > 0 ? "DATETIME(" + fsp + ")" : "DATETIME";
                yield MappedType.warn(dt, "timezone info lost");
            }
            case "char" -> MappedType.of("CHAR(" + (len != null && len > 0 ? len : 1) + ")");
            case "varchar" -> mapVarchar(len, false);
            case "text" -> MappedType.warn("LONGTEXT", "");
            case "nchar" -> {
                int charLen = (len != null && len > 0) ? len / 2 : 1;
                yield MappedType.of("CHAR(" + charLen + ") CHARACTER SET utf8mb4");
            }
            case "nvarchar" -> mapVarchar(len, true);
            case "ntext" -> MappedType.warn("LONGTEXT", "");
            case "binary" -> MappedType.of("BINARY(" + (len != null && len > 0 ? len : 1) + ")");
            case "varbinary" -> {
                if (len != null && len == -1) {
                    yield MappedType.warn("LONGBLOB", "");
                } else {
                    int vbLen = (len != null && len > 0) ? len : 1;
                    yield MappedType.of("VARBINARY(" + vbLen + ")");
                }
            }
            case "image" -> MappedType.warn("LONGBLOB", "deprecated type");
            case "uniqueidentifier" -> MappedType.warn("VARCHAR(36)", "");
            case "rowversion", "timestamp" -> MappedType.warn("VARBINARY(8)", "row version");
            case "xml" -> MappedType.warn("LONGTEXT", "");
            case "json" -> MappedType.of("JSON");
            case "vector" -> MappedType.warn("VECTOR", "requires TiDB v8.4+");
            case "hierarchyid" -> MappedType.warn("VARCHAR(4000)", "");
            case "geography" -> MappedType.warn("LONGBLOB", "spatial operations not available");
            case "geometry" -> MappedType.warn("LONGBLOB", "spatial operations not available");
            case "sql_variant" -> MappedType.warn("LONGTEXT", "");
            case "sysname" -> MappedType.of("VARCHAR(128)");
            case "cursor", "table" -> MappedType.skip(type.toUpperCase() + " is not applicable to table columns");
            default -> MappedType.skip("Unknown type '" + type + "' — column skipped");
        };
    }

    private MappedType mapVarchar(Integer len, boolean unicode) {
        String charset = unicode ? " CHARACTER SET utf8mb4" : "";
        if (len == null || len == -1) {
            return MappedType.warn("LONGTEXT", "");
        }
        int charLen = unicode ? len / 2 : len;
        return MappedType.of("VARCHAR(" + charLen + ")" + charset);
    }

    @Override
    public String mapDefaultValue(String rawDefault) {
        if (rawDefault == null) return null;
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
            case "SYSUTCDATETIME()" -> "UTC_TIMESTAMP()";
            case "SYSDATETIMEOFFSET()" -> "CURRENT_TIMESTAMP";
            default -> stripNPrefix(val);
        };
    }

    /** SQL Server nvarchar defaults come as N'...' — TiDB doesn't recognize the N prefix. */
    private static String stripNPrefix(String val) {
        if (val.startsWith("N'") && val.endsWith("'") && val.length() >= 3) {
            return val.substring(1);
        }
        return val;
    }
}
