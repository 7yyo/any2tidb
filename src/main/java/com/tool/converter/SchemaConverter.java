package com.tool.converter;

import com.tool.ConversionResult;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SchemaConverter {

    private final TypeMapper typeMapper;

    public SchemaConverter(TypeMapper typeMapper) { this.typeMapper = typeMapper; }

    /**
     * Produces the full DDL for a table (CREATE TABLE + CREATE INDEX statements).
     */
    public String toCreateTableDDL(TableSchema table, ConversionResult result, boolean dropIfExists) {
        StringBuilder sb = new StringBuilder();

        if (table.isPartitioned()) {
            result.addWarning("partitioned table detected, converted to regular table");
        }
        if (table.getForeignKeyCount() > 0) {
            result.addWarning(table.getForeignKeyCount() + " foreign key(s) discarded");
        }

        if (dropIfExists) {
            sb.append("DROP TABLE IF EXISTS `").append(table.getTableName()).append("`;\n\n");
        }

        sb.append("CREATE TABLE `").append(table.getTableName()).append("` (\n");

        List<String> parts = new ArrayList<>();

        // Columns — first pass: check for any unskippable (skip) columns
        List<String> skipCols = new ArrayList<>();
        for (ColumnSchema col : table.getColumns()) {
            TypeMapper.MappedType mapped = typeMapper.mapType(col);
            if (mapped.skip()) skipCols.add("'" + col.getName() + "' (" + mapped.warningMessage() + ")");
        }
        if (!skipCols.isEmpty()) {
            result.setError("table skipped — " + skipCols.size() + " column(s) cannot be converted: "
                    + String.join(", ", skipCols) + ". Manual intervention required.");
            return null;
        }

        // Columns — second pass: build column definitions
        for (ColumnSchema col : table.getColumns()) {
            if (col.isComputed()) {
                result.addWarning("column '" + col.getName() + "': computed column (AS expr) converted to plain column — verify definition manually");
            }
            TypeMapper.MappedType mapped = typeMapper.mapType(col);
            if (mapped.hasWarning()) {
                result.addWarning("column '" + col.getName() + "': " + mapped.warningMessage());
            }

            StringBuilder colDef = new StringBuilder();
            colDef.append("  `").append(col.getName()).append("` ").append(mapped.tidbType());
            if (!col.isNullable()) colDef.append(" NOT NULL");
            if (col.isIdentity()) colDef.append(" AUTO_INCREMENT");

            String rawDefault = col.getDefaultValue();
            String defaultVal = typeMapper.mapDefaultValue(rawDefault);
            // Derive the precision of DATETIME columns from the mapped TiDB type (e.g. DATETIME(6) → 6)
            // so that CURRENT_TIMESTAMP and UTC_TIMESTAMP fallbacks always match the column precision.
            String tidbType = mapped.tidbType().trim().toUpperCase();
            int datetimePrecision = 0;
            if (tidbType.startsWith("DATETIME(") && tidbType.endsWith(")")) {
                try {
                    datetimePrecision = Integer.parseInt(tidbType.substring("DATETIME(".length(), tidbType.length() - 1));
                } catch (NumberFormatException ignored) { }
            }
            // If the mapped default is CURRENT_TIMESTAMP but the column has explicit precision,
            // TiDB requires the precision to match: CURRENT_TIMESTAMP(n)
            if ("CURRENT_TIMESTAMP".equals(defaultVal) && datetimePrecision > 0) {
                defaultVal = "CURRENT_TIMESTAMP(" + datetimePrecision + ")";
            }
            // TiDB rejects CURRENT_TIMESTAMP on DATE/TIME columns; use date/time-appropriate functions
            if ("CURRENT_TIMESTAMP".equals(defaultVal) || (defaultVal != null && defaultVal.startsWith("CURRENT_TIMESTAMP("))) {
                if (tidbType.equals("DATE")) {
                    defaultVal = "CURDATE()";
                } else if (tidbType.equals("TIME")) {
                    // TiDB does not support function-based defaults for TIME; drop the default
                    result.addWarning("column '" + col.getName() + "': TIME column default '" + rawDefault
                            + "' cannot be expressed as a TiDB function default — default dropped");
                    defaultVal = null;
                } else if (tidbType.equals("DATETIME") && defaultVal != null && defaultVal.startsWith("CURRENT_TIMESTAMP(")) {
                    // DATETIME (no precision) cannot use CURRENT_TIMESTAMP(n); strip precision
                    defaultVal = "CURRENT_TIMESTAMP";
                }
            }
            // TiDB does not support UTC_TIMESTAMP() as a column DEFAULT expression;
            // fall back to CURRENT_TIMESTAMP (or CURRENT_TIMESTAMP(n) for datetime2 columns)
            if ("UTC_TIMESTAMP()".equals(defaultVal)) {
                result.addWarning("column '" + col.getName() + "': UTC_TIMESTAMP() is not supported as a TiDB DEFAULT — "
                        + "falling back to CURRENT_TIMESTAMP (local time, not UTC)");
                if (datetimePrecision > 0) {
                    defaultVal = "CURRENT_TIMESTAMP(" + datetimePrecision + ")";
                } else {
                    defaultVal = "CURRENT_TIMESTAMP";
                }
            }
            if ("UUID()".equals(defaultVal)) {
                result.addWarning("column '" + col.getName() + "': UUID() is not supported as a TiDB DEFAULT expression — default dropped");
                defaultVal = null;
            }
            // BLOB/TEXT/JSON/LONGTEXT/LONGBLOB columns cannot have a default value in TiDB/MySQL
            if (defaultVal != null) {
                String baseType = tidbType.replaceAll("\\(.*\\)", "").trim();
                if (baseType.equals("LONGTEXT") || baseType.equals("LONGBLOB") ||
                        baseType.equals("TEXT") || baseType.equals("BLOB") ||
                        baseType.equals("MEDIUMTEXT") || baseType.equals("MEDIUMBLOB") ||
                        baseType.equals("JSON")) {
                    result.addWarning("column '" + col.getName() + "': " + baseType
                            + " column cannot have a default value in TiDB — default dropped");
                    defaultVal = null;
                }
            }
            // Drop any default that is still a function call that TiDB cannot evaluate as a DEFAULT
            // (e.g. lower(), dbo.fn_xxx()). Only well-known safe mappings and literal values survive.
            if (defaultVal != null && defaultVal.contains("(")) {
                boolean isKnownSafeFunction =
                        defaultVal.startsWith("CURRENT_TIMESTAMP") ||
                        defaultVal.equals("CURDATE()") ||
                        defaultVal.equals("UUID()");
                if (!isKnownSafeFunction) {
                    result.addWarning("column '" + col.getName() + "': default value '"
                            + rawDefault.trim() + "' contains a function not supported as a TiDB DEFAULT — default dropped");
                    defaultVal = null;
                }
            }
            // Drop numeric/boolean literal defaults on temporal columns — TiDB rejects them ([1067])
            if (defaultVal != null && isTemporalTidbType(tidbType) && isNumericLiteral(defaultVal)) {
                result.addWarning("column '" + col.getName() + "': numeric default '" + defaultVal
                        + "' is not valid for " + tidbType + " column — default dropped");
                defaultVal = null;
            }
            // Drop empty/blank string defaults on temporal columns — TiDB rejects them ([1067])
            if (defaultVal != null && isTemporalTidbType(tidbType) && isEmptyStringLiteral(defaultVal)) {
                result.addWarning("column '" + col.getName() + "': empty string default is not valid for "
                        + tidbType + " column — default dropped");
                defaultVal = null;
            }
            // Drop empty-string defaults on numeric columns — SQL Server silently coerces '' to 0,
            // but TiDB rejects them with [1067] Invalid default value ([1067])
            if (defaultVal != null && isNumericTidbType(tidbType) && isEmptyStringLiteral(defaultVal)) {
                result.addWarning("column '" + col.getName() + "': empty string default is not valid for "
                        + tidbType + " column (SQL Server coerces '' to 0) — default dropped");
                defaultVal = null;
            }
            if (defaultVal != null && !col.isIdentity()) {
                colDef.append(" DEFAULT ").append(defaultVal);
            }
            if (col.getComment() != null && !col.getComment().isBlank()) {
                colDef.append(" COMMENT '").append(col.getComment().replace("'", "''")).append("'");
            }
            parts.add(colDef.toString());
        }

        // Primary key
        if (!table.getPrimaryKeyColumns().isEmpty()) {
            parts.add("  PRIMARY KEY (" + quoteCols(table.getPrimaryKeyColumns()) + ")");
        }

        // Unique constraints
        for (Map.Entry<String, String> uc : table.getUniqueConstraints().entrySet()) {
            List<String> cols = List.of(uc.getValue().split(","));
            parts.add("  UNIQUE KEY `" + uc.getKey() + "` (" + quoteCols(cols) + ")");
        }

        // Check constraints — TiDB disables check enforcement by default, discard them
        if (!table.getCheckConstraints().isEmpty()) {
            result.addWarning(table.getCheckConstraints().size() + " CHECK constraint(s) discarded (TiDB does not enforce CHECK by default)");
        }

        sb.append(String.join(",\n", parts));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        // Indexes (appended after CREATE TABLE)
        for (IndexSchema idx : table.getIndexes()) {
            String indexDdl = buildIndexDDL(table.getTableName(), idx, result);
            if (indexDdl != null) sb.append("\n").append(indexDdl).append("\n");
        }

        return sb.toString();
    }

    private String buildIndexDDL(String tableName, IndexSchema idx, ConversionResult result) {
        if (idx.isFulltext()) {
            result.addWarning("FULLTEXT index '" + idx.getName() + "' discarded — not supported in TiDB");
            return null;
        }
        if (idx.isColumnstore()) {
            result.addWarning("COLUMNSTORE index '" + idx.getName() + "' discarded — not supported in TiDB");
            return null;
        }
        if (idx.getColumns() == null || idx.getColumns().isEmpty()) return null;

        if (idx.isClustered()) {
            result.addWarning("CLUSTERED index '" + idx.getName() + "' converted to regular INDEX");
        }
        if (idx.getIncludeColumns() != null && !idx.getIncludeColumns().isEmpty()) {
            result.addWarning("index '" + idx.getName() + "': INCLUDE columns dropped — not supported in TiDB");
        }
        if (idx.getFilterDefinition() != null) {
            result.addWarning("index '" + idx.getName() + "': WHERE filter dropped — filtered indexes not supported in TiDB");
        }

        String uniqueKeyword = idx.isUnique() ? "UNIQUE " : "";
        return "CREATE " + uniqueKeyword + "INDEX `" + idx.getName() + "` ON `" + tableName + "` ("
                + quoteCols(idx.getColumns()) + ");";
    }

    private String quoteCols(List<String> cols) {
        return cols.stream().map(c -> "`" + c.trim() + "`").collect(Collectors.joining(", "));
    }

    private static boolean isTemporalTidbType(String tidbType) {
        return tidbType.equals("DATE") || tidbType.equals("TIME") ||
               tidbType.equals("DATETIME") || tidbType.startsWith("DATETIME(");
    }

    private static boolean isNumericTidbType(String tidbType) {
        String base = tidbType.replaceAll("\\(.*\\)", "").trim();
        // Strip UNSIGNED / SIGNED qualifiers (e.g. "TINYINT UNSIGNED" → "TINYINT")
        base = base.replaceAll("(?i)\\s+(UNSIGNED|SIGNED)$", "").trim();
        return base.equals("INT") || base.equals("BIGINT") || base.equals("SMALLINT") ||
               base.equals("TINYINT") || base.equals("DECIMAL") || base.equals("NUMERIC") ||
               base.equals("FLOAT") || base.equals("DOUBLE") || base.equals("REAL");
    }

    private static final Pattern NUMERIC_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static boolean isNumericLiteral(String val) {
        return NUMERIC_LITERAL.matcher(val.trim()).matches();
    }

    private static boolean isEmptyStringLiteral(String val) {
        String t = val.trim();
        return t.equals("''") || t.equals("\"\"");
    }
}
