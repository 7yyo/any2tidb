package com.tool.pipeline;

/**
 * One discrete stage of the migration pipeline.
 * Implementations read what they need from {@code ctx} and write
 * their outputs back into it for downstream steps to consume.
 */
@FunctionalInterface
public interface MigrationStep {
    /**
     * @return {@link StepResult#ok} on success (pipeline continues),
     *         {@link StepResult#fatal} on unrecoverable failure (pipeline stops).
     */
    StepResult execute(StepContext ctx) throws Exception;

    /** Human-readable name shown in logs. Override for clearer output. */
    default String name() { return getClass().getSimpleName(); }
}
