package com.tool.pipeline;

import java.util.Objects;

public record StepResult(boolean isFatal, String message) {
    public StepResult {
        Objects.requireNonNull(message, "message must not be null");
    }
    public static StepResult ok(String message)    { return new StepResult(false, message); }
    public static StepResult fatal(String message) { return new StepResult(true,  message); }
}
