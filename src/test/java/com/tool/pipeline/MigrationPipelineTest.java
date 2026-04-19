package com.tool.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
}
