package com.tool.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;

class MigrationPipelineTest {

    @Test
    void stepResult_ok_isNotFatal() {
        StepResult r = StepResult.ok("done");
        assertFalse(r.isFatal());
        assertEquals("done", r.message());
    }

    @Test
    void stepResult_fatal_isFatal() {
        StepResult r = StepResult.fatal("boom");
        assertTrue(r.isFatal());
        assertEquals("boom", r.message());
    }

    @Test
    void stepContext_storeAndRetrieve() {
        StepContext ctx = new StepContext();
        ctx.put("key", "value");
        assertEquals("value", ctx.get("key", String.class));
    }

    @Test
    void stepContext_getMissing_returnsNull() {
        StepContext ctx = new StepContext();
        assertNull(ctx.get("missing", String.class));
    }

    @Test
    void stepContext_has_trueAfterPut() {
        StepContext ctx = new StepContext();
        ctx.put("x", 42);
        assertTrue(ctx.has("x"));
    }

    @Test
    void stepContext_has_falseForMissing() {
        StepContext ctx = new StepContext();
        assertFalse(ctx.has("nope"));
    }

    @Test
    void pipeline_runsStepsInOrder() throws Exception {
        List<String> log = new ArrayList<>();
        MigrationStep s1 = ctx -> { log.add("s1"); return StepResult.ok("step1 done"); };
        MigrationStep s2 = ctx -> { log.add("s2"); return StepResult.ok("step2 done"); };

        new MigrationPipeline(List.of(s1, s2)).run(new StepContext());

        assertEquals(List.of("s1", "s2"), log);
    }

    @Test
    void pipeline_stopOnFatal() throws Exception {
        List<String> log = new ArrayList<>();
        MigrationStep s1 = ctx -> { log.add("s1"); return StepResult.fatal("fatal!"); };
        MigrationStep s2 = ctx -> { log.add("s2"); return StepResult.ok("ok"); };

        new MigrationPipeline(List.of(s1, s2)).run(new StepContext());

        assertEquals(List.of("s1"), log, "s2 must not run after fatal");
    }

    @Test
    void pipeline_emptySteps_runsOk() throws Exception {
        StepResult result = new MigrationPipeline(List.of()).run(new StepContext());
        assertFalse(result.isFatal());
    }
}
