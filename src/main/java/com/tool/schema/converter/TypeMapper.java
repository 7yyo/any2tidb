package com.tool.schema.converter;

import com.tool.common.model.ColumnSchema;

public interface TypeMapper {

    record MappedType(String tidbType, boolean hasWarning, boolean skip, String warningMessage) {
        public static MappedType of(String type) { return new MappedType(type, false, false, null); }
        public static MappedType warn(String type, String msg) { return new MappedType(type, true, false, msg); }
        public static MappedType skip(String msg) { return new MappedType(null, true, true, msg); }
    }

    MappedType mapType(ColumnSchema col);

    String mapDefaultValue(String rawDefault);
}
