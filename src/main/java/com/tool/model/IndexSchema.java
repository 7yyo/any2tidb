package com.tool.model;

import java.util.List;

public class IndexSchema {
    private String name;
    private boolean unique;
    private boolean clustered;
    private boolean fulltext;
    private boolean columnstore;
    private List<String> columns;
    private List<String> includeColumns;  // INCLUDE clause columns
    private String filterDefinition;      // WHERE clause for filtered indexes

    public IndexSchema() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
    public boolean isClustered() { return clustered; }
    public void setClustered(boolean clustered) { this.clustered = clustered; }
    public boolean isFulltext() { return fulltext; }
    public void setFulltext(boolean fulltext) { this.fulltext = fulltext; }
    public boolean isColumnstore() { return columnstore; }
    public void setColumnstore(boolean columnstore) { this.columnstore = columnstore; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<String> getIncludeColumns() { return includeColumns; }
    public void setIncludeColumns(List<String> includeColumns) { this.includeColumns = includeColumns; }
    public String getFilterDefinition() { return filterDefinition; }
    public void setFilterDefinition(String filterDefinition) { this.filterDefinition = filterDefinition; }
}
