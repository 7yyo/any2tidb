package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;

import java.util.List;

/**
 * Pre-flight validation: checks that source and target hosts are configured,
 * then publishes resolved config values into StepContext for downstream steps.
 *
 * Reads from context:
 *   "dryRun"        (Boolean)       — set by App before pipeline.run()
 *   "tablesOverride" (List<String>)  — optional CLI override, may be null
 *
 * Writes to context:
 *   "dryRun"          (Boolean)       — re-published after null-safe normalization
 *   "schemas"        (List<String>)
 *   "tables"         (List<String>)
 *   "dropIfExists"   (Boolean)
 *   "continueOnError" (Boolean)
 */
public class PreCheckStep implements MigrationStep {

    private final AppConfig config;

    public PreCheckStep(AppConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "PreCheck"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) {
        String srcHost = config.getSource().getHost();
        if (srcHost == null || srcHost.isBlank()) {
            return StepResult.fatal("source.host is not configured in application.yml");
        }
        String tgtHost = config.getTarget().getHost();
        if (tgtHost == null || tgtHost.isBlank()) {
            return StepResult.fatal("target.host is not configured in application.yml");
        }

        Boolean dryRun = ctx.get("dryRun", Boolean.class);
        List<String> tablesOverride = ctx.get("tablesOverride", List.class);
        List<String> schemas = config.getConvert().getSchemas();
        List<String> tables  = tablesOverride != null ? tablesOverride : config.getConvert().getTables();

        ctx.put("dryRun",          dryRun != null && dryRun);
        ctx.put("schemas",         schemas  != null ? schemas : List.of());
        ctx.put("tables",          tables   != null ? tables  : List.of());
        ctx.put("dropIfExists",    config.getConvert().isDropIfExists());
        ctx.put("continueOnError", config.getConvert().isContinueOnError());

        return StepResult.ok("config validated");
    }
}
