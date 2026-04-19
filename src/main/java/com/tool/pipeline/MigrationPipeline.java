package com.tool.pipeline;

import java.util.List;

/**
 * Runs a fixed sequence of {@link MigrationStep}s against a shared {@link StepContext}.
 * Stops immediately when any step returns {@link StepResult#isFatal()}.
 */
public class MigrationPipeline {

    private final List<MigrationStep> steps;

    public MigrationPipeline(List<MigrationStep> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * Execute all steps in order.
     * @param ctx shared context threaded through every step
     * @return the result of the last step executed
     */
    public StepResult run(StepContext ctx) throws Exception {
        StepResult last = StepResult.ok("no steps");
        for (MigrationStep step : steps) {
            try {
                last = step.execute(ctx);
            } catch (Exception e) {
                last = StepResult.fatal("[" + step.name() + "] " + e.getMessage());
            }
            if (last.isFatal()) break;
        }
        return last;
    }
}
