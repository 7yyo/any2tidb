package com.tool.common.model;

import java.util.ArrayList;
import java.util.List;

public class ConversionResult {
    public enum Status { OK, WARN, ERROR, SKIP }

    private final String tableName;
    private Status status = Status.OK;
    private final List<Warning> warnings = new ArrayList<>();
    private String errorMessage;

    public ConversionResult(String tableName) { this.tableName = tableName; }

    public void addWarning(String msg, String... kvs) {
        warnings.add(new Warning(msg, kvs));
        if (status == Status.OK) status = Status.WARN;
    }

    public void setError(String message)  { this.errorMessage = sanitize(message); this.status = Status.ERROR; }
    public void setSkip(String message)   { this.errorMessage = sanitize(message); this.status = Status.SKIP; }

    private static String sanitize(String s) {
        return s != null ? s.replace('\n', ' ').replace('\r', ' ') : null;
    }

    public String getTableName()    { return tableName; }
    public Status getStatus()       { return status; }
    public List<Warning> getWarnings() { return warnings; }
    public String getErrorMessage() { return errorMessage; }
}
