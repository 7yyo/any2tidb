package com.tool.pipeline;

public record StepResult(boolean isFatal, String message) {
    public static StepResult ok(String message)    { return new StepResult(false, message); }
    public static StepResult fatal(String message) { return new StepResult(true,  message); }
}
