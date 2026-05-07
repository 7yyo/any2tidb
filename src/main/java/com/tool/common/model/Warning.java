package com.tool.common.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured warning: short action message + key=value fields.
 * Usage: new Warning("default dropped", "col", "NOTES", "type", "LONGTEXT")
 */
public class Warning {
    private final String msg;
    private final Map<String, String> fields;

    public Warning(String msg, String... kvs) {
        this.msg = msg;
        this.fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            fields.put(kvs[i], String.valueOf(kvs[i + 1]));
        }
    }

    public String msg() { return msg; }
    public Map<String, String> fields() { return fields; }
}
