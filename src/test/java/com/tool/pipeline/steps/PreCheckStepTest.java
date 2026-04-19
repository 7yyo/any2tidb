package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PreCheckStepTest {

    private static AppConfig configWith(String srcHost, String tgtHost) {
        AppConfig cfg = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost(srcHost); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        AppConfig.DbConfig tgt = new AppConfig.DbConfig();
        tgt.setHost(tgtHost); tgt.setPort(4000);
        tgt.setUsername("root"); tgt.setPassword("");
        cfg.setSource(src);
        cfg.setTarget(tgt);
        AppConfig.ConvertConfig cc = new AppConfig.ConvertConfig();
        cc.setSchemas(List.of());
        cc.setTables(List.of());
        cfg.setConvert(cc);
        return cfg;
    }

    @Test
    void validConfig_producesOkResult() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("127.0.0.1", "127.0.0.1")).execute(ctx);

        assertFalse(r.isFatal());
    }

    @Test
    void validConfig_populatesContextKeys() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        new PreCheckStep(configWith("127.0.0.1", "127.0.0.1")).execute(ctx);

        assertNotNull(ctx.get("schemas", List.class));
        assertNotNull(ctx.get("tables", List.class));
        assertFalse((Boolean) ctx.get("dryRun", Boolean.class));
        assertNotNull(ctx.get("dropIfExists", Boolean.class));
        assertNotNull(ctx.get("continueOnError", Boolean.class));
    }

    @Test
    void blankSourceHost_returnsFatal() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("", "127.0.0.1")).execute(ctx);

        assertTrue(r.isFatal());
        assertTrue(r.message().contains("source.host"));
    }

    @Test
    void blankTargetHost_returnsFatal() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("127.0.0.1", "")).execute(ctx);

        assertTrue(r.isFatal());
        assertTrue(r.message().contains("target.host"));
    }
}
