package com.tool.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskState {
    CREATED,
    DUMPING,
    DUMPED,
    SNAPSHOTTING,
    SNAPSHOT,
    SYNCING,
    FAILED;

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static TaskState fromString(String s) {
        return valueOf(s.toUpperCase());
    }

    public boolean canTransitionTo(TaskState next) {
        if (next == null) return false;
        return switch (this) {
            case CREATED      -> next == DUMPING || next == SNAPSHOTTING || next == FAILED;
            case DUMPING      -> next == DUMPED  || next == FAILED;
            case DUMPED       -> next == FAILED;
            case SNAPSHOTTING -> next == SNAPSHOT || next == FAILED;
            case SNAPSHOT     -> next == SYNCING || next == FAILED;
            case SYNCING      -> next == FAILED;
            case FAILED       -> next == DUMPING || next == SNAPSHOTTING || next == SYNCING; // resume
        };
    }
}
