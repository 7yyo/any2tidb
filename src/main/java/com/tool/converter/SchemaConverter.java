package com.tool.converter;

import com.tool.ConversionResult;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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

        // Columns
        for (ColumnSchema col : table.getColumns()) {
            TypeMapper.MappedType mapped = typeMapper.mapType(col);
            if (mapped.skip()) {
                result.addWarning("column '" + col.getName() + "': " + mapped.warningMessage() + " — column skipped");
                continue;
            }
            if (mapped.hasWarning()) {
                result.addWarning("column '" + col.getName() + "': " + mapped.warningMessage());
            }

            StringBuilder colDef = new StringBuilder();
            colDef.append("  `").append(col.getName()).append("` ").append(mapped.tidbType());
            if (!col.isNullable()) colDef.append(" NOT NULL");
            if (col.isIdentity()) colDef.append(" AUTO_INCREMENT");

            String rawDefault = col.getDefaultValue();
            String defaultVal = typeMapper.mapDefaultValue(rawDefault);
            if (defaultVal != null && !col.isIdentity()) {
                colDef.append(" DEFAULT ").append(defaultVal);
                // Warn if the original default was a function call that required translation
                if (rawDefault != null && rawDefault.contains("(")) {
                    result.addWarning("column '" + col.getName() + "': default value '"
                            + rawDefault.trim() + "' translated to '" + defaultVal + "' — verify semantics");
                }
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
        for (String uniqueCols : table.getUniqueConstraintColumns()) {
            List<String> cols = List.of(uniqueCols.split(","));
            parts.add("  UNIQUE KEY (" + quoteCols(cols) + ")");
        }

        // Check constraints
        for (String check : table.getCheckConstraints()) {
            String expr = check.trim();
            if (expr.startsWith("(") && expr.endsWith(")")) expr = expr.substring(1, expr.length() - 1);
            parts.add("  CHECK (" + expr + ")");
            result.addWarning("CHECK constraint included — requires TiDB 8.0+");
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
}
