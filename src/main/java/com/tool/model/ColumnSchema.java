package com.tool.model;

public class ColumnSchema {
    private String name;
    private String sqlServerType;   // raw type from SQL Server, e.g. "nvarchar"
    private Integer maxLength;      // character max length, null if not applicable
    private Integer precision;      // numeric precision
    private Integer scale;          // numeric scale
    private boolean nullable;
    private String defaultValue;    // raw default expression from SQL Server
    private boolean identity;       // AUTO_INCREMENT equivalent

    public ColumnSchema() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSqlServerType() { return sqlServerType; }
    public void setSqlServerType(String sqlServerType) { this.sqlServerType = sqlServerType; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public Integer getPrecision() { return precision; }
    public void setPrecision(Integer precision) { this.precision = precision; }
    public Integer getScale() { return scale; }
    public void setScale(Integer scale) { this.scale = scale; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public boolean isIdentity() { return identity; }
    public void setIdentity(boolean identity) { this.identity = identity; }
}
