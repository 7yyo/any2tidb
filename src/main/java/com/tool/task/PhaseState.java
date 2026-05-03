package com.tool.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PhaseState {
    PENDING,
    RUNNING,
    DONE,
    FAILED;

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static PhaseState fromString(String s) {
        return valueOf(s.toUpperCase());
    }
}
