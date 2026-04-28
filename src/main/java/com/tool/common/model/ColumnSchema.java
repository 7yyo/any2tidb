package com.tool.common.model;

public class ColumnSchema {
    private String name;
    private String sourceType;      // raw type from source database, e.g. "nvarchar"
    private Integer maxLength;      // character max length, null if not applicable
    private Integer precision;      // numeric precision
    private Integer scale;          // numeric scale
    private boolean nullable;
    private String defaultValue;    // raw default expression from source database
    private boolean identity;       // AUTO_INCREMENT equivalent
    private boolean computed;       // AS (expr) computed column
    private String comment;         // extended property (e.g. MS_Description)

    public ColumnSchema() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
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
    public boolean isComputed() { return computed; }
    public void setComputed(boolean computed) { this.computed = computed; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
