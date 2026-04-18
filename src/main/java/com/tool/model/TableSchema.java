package com.tool.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TableSchema {
    private String schemaName;      // e.g. "dbo"
    private String tableName;       // e.g. "orders"
    private List<ColumnSchema> columns = new ArrayList<>();
    private List<String> primaryKeyColumns = new ArrayList<>();
    private List<String> checkConstraints = new ArrayList<>();  // raw CHECK expressions
    private Map<String, String> uniqueConstraints = new LinkedHashMap<>();  // constraintName -> CSV of column names
    private List<IndexSchema> indexes = new ArrayList<>();
    private boolean partitioned = false;
    private int foreignKeyCount = 0;

    public TableSchema() {}

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public List<ColumnSchema> getColumns() { return columns; }
    public void setColumns(List<ColumnSchema> columns) { this.columns = columns; }
    public List<String> getPrimaryKeyColumns() { return primaryKeyColumns; }
    public void setPrimaryKeyColumns(List<String> primaryKeyColumns) { this.primaryKeyColumns = primaryKeyColumns; }
    public List<String> getCheckConstraints() { return checkConstraints; }
    public void setCheckConstraints(List<String> checkConstraints) { this.checkConstraints = checkConstraints; }
    public Map<String, String> getUniqueConstraints() { return uniqueConstraints; }
    public void setUniqueConstraints(Map<String, String> uniqueConstraints) { this.uniqueConstraints = uniqueConstraints; }
    public List<IndexSchema> getIndexes() { return indexes; }
    public void setIndexes(List<IndexSchema> indexes) { this.indexes = indexes; }
    public boolean isPartitioned() { return partitioned; }
    public void setPartitioned(boolean partitioned) { this.partitioned = partitioned; }
    public int getForeignKeyCount() { return foreignKeyCount; }
    public void setForeignKeyCount(int foreignKeyCount) { this.foreignKeyCount = foreignKeyCount; }

    public String getFullName() { return schemaName + "." + tableName; }
}
