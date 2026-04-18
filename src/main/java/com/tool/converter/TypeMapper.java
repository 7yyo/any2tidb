package com.tool.converter;

import com.tool.model.ColumnSchema;
import org.springframework.stereotype.Component;

@Component
public class TypeMapper {

    public record MappedType(String tidbType, boolean hasWarning, boolean skip, String warningMessage) {
        public static MappedType of(String type) { return new MappedType(type, false, false, null); }
        public static MappedType warn(String type, String msg) { return new MappedType(type, true, false, msg); }
        public static MappedType skip(String msg) { return new MappedType(null, true, true, msg); }
    }

    public MappedType mapType(ColumnSchema col) {
        String type = col.getSqlServerType().toLowerCase().trim();
        Integer len = col.getMaxLength();
        Integer prec = col.getPrecision();
        Integer scale = col.getScale();

        return switch (type) {
            case "int", "integer" -> MappedType.of("INT");
            case "bigint" -> MappedType.of("BIGINT");
            case "smallint" -> MappedType.of("SMALLINT");
            case "tinyint" -> MappedType.of("TINYINT UNSIGNED");
            case "bit" -> MappedType.of("TINYINT(1)");
            case "decimal", "numeric" -> MappedType.of("DECIMAL(" + prec + "," + scale + ")");
            case "money" -> MappedType.warn("DECIMAL(19,4)", "MONEY converted to DECIMAL(19,4)");
            case "smallmoney" -> MappedType.warn("DECIMAL(10,4)", "SMALLMONEY converted to DECIMAL(10,4)");
            case "float" -> MappedType.of("DOUBLE");
            case "real" -> MappedType.of("FLOAT");
            case "date" -> MappedType.of("DATE");
            case "time" -> MappedType.of("TIME");
            case "datetime", "smalldatetime" -> MappedType.of("DATETIME");
            case "datetime2" -> MappedType.warn("DATETIME", "DATETIME2 converted to DATETIME, precision may be lost");
            case "datetimeoffset" -> MappedType.warn("DATETIME", "DATETIMEOFFSET converted to DATETIME, timezone info lost");
            case "char" -> MappedType.of("CHAR(" + len + ")");
            case "varchar" -> mapVarchar(len, false);
            case "text" -> MappedType.warn("LONGTEXT", "TEXT converted to LONGTEXT");
            case "nchar" -> MappedType.of("CHAR(" + (len != null && len != -1 ? len / 2 : len) + ") CHARACTER SET utf8mb4");
            case "nvarchar" -> mapVarchar(len, true);
            case "ntext" -> MappedType.warn("LONGTEXT", "NTEXT converted to LONGTEXT");
            case "binary" -> MappedType.of("BINARY(" + len + ")");
            case "varbinary" -> len != null && len == -1
                    ? MappedType.warn("LONGBLOB", "VARBINARY(MAX) converted to LONGBLOB")
                    : MappedType.of("VARBINARY(" + len + ")");
            case "image" -> MappedType.warn("LONGBLOB", "IMAGE converted to LONGBLOB (deprecated type)");
            case "uniqueidentifier" -> MappedType.warn("VARCHAR(36)", "UNIQUEIDENTIFIER converted to VARCHAR(36)");
            case "rowversion", "timestamp" -> MappedType.warn("BIGINT UNSIGNED", "ROWVERSION/TIMESTAMP (row version) converted to BIGINT UNSIGNED");
            case "xml" -> MappedType.warn("LONGTEXT", "XML converted to LONGTEXT");
            case "json" -> MappedType.of("JSON");
            case "vector" -> MappedType.warn("VECTOR", "VECTOR requires TiDB v8.4+");
            case "hierarchyid" -> MappedType.warn("VARCHAR(4000)", "HIERARCHYID converted to VARCHAR(4000)");
            case "geography" -> MappedType.warn("GEOMETRY", "GEOGRAPHY converted to GEOMETRY");
            case "geometry" -> MappedType.of("GEOMETRY");
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
        // Strip SQL Server wrapping parens: ((value)) or (value)
        String val = rawDefault.trim();
        while (val.startsWith("(") && val.endsWith(")")) {
            val = val.substring(1, val.length() - 1).trim();
        }
        return switch (val.toUpperCase()) {
            case "GETDATE()" -> "CURRENT_TIMESTAMP";
            case "GETUTCDATE()" -> "UTC_TIMESTAMP()";
            case "NEWID()" -> "UUID()";
            case "NEWSEQUENTIALID()" -> "UUID()";
            case "SYSDATETIME()" -> "CURRENT_TIMESTAMP(6)";
            default -> val;
        };
    }
}
