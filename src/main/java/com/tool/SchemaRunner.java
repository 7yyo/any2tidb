package com.tool;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.output.ProgressReporter;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.pipeline.steps.SchemaMigrateStep;
import com.tool.pipeline.steps.VerifyStep;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.writer.SchemaWriter;
import com.tool.source.SourceDriver;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class SchemaRunner {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final SchemaVerifier verifier;
    private final SourceDriver driver;

    SchemaRunner(AppConfig config, SchemaExtractor extractor, SchemaConverter converter,
                 SchemaWriter writer, SchemaVerifier verifier, SourceDriver driver) {
        this.config = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer = writer;
        this.verifier = verifier;
        this.driver = driver;
    }

    void run(ApplicationArguments args, List<String> databases, List<String> tables,
             boolean dryRun, boolean dropIfExists) throws Exception {
        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
        Log.info(log, "Connection info",
                "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                "mode", "schema");

        StepContext ctx = new StepContext();
        ctx.put("dryRun", dryRun);
        ctx.put("databases", databases != null ? databases : List.of());
        ctx.put("tables", tables != null ? tables : List.of());
        ctx.put("dropIfExists", dropIfExists);

        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.createInteractive(taskName, "schema", "sqlserver");
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);
        taskManager.writeMeta(taskName, meta);
        App.resolveTaskPaths(taskName, taskManager, ctx);
        ctx.put("taskManager", taskManager);
        ctx.put("taskName", taskName);

        ProgressReporter progress = new ProgressReporter();
        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, driver));
        steps.add(new SchemaMigrateStep(config, extractor, converter, writer, progress, driver));
        steps.add(new VerifyStep(config, verifier, driver));

        StepResult result = new MigrationPipeline(steps).run(ctx);
        App.handleResult(result, ctx);

        try {
            if (!result.isFatal()) {
                meta.markSuccess();
            } else {
                meta.markFailed(result.message());
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
    }
}
