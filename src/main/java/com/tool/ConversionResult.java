package com.tool;

import java.util.ArrayList;
import java.util.List;

public class ConversionResult {
    public enum Status { OK, WARN, ERROR }

    private final String tableName;
    private Status status = Status.OK;
    private final List<String> warnings = new ArrayList<>();
    private String errorMessage;

    public ConversionResult(String tableName) { this.tableName = tableName; }

    public void addWarning(String message) {
        warnings.add(message);
        if (status == Status.OK) status = Status.WARN;
    }

    public void setError(String message) { this.errorMessage = message; this.status = Status.ERROR; }

    public String getTableName() { return tableName; }
    public Status getStatus() { return status; }
    public List<String> getWarnings() { return warnings; }
    public String getErrorMessage() { return errorMessage; }
}
