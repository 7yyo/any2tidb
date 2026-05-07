package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;

/**
 * MySQL → TiDB type mapper.
 * TiDB is MySQL-compatible — all types pass through unchanged.
 */
public class MySqlTypeMapper implements TypeMapper {

    @Override
    public MappedType mapType(ColumnSchema col) {
        return MappedType.of(col.getSourceType());
    }

    @Override
    public String mapDefaultValue(String rawDefault) {
        return rawDefault;
    }
}
