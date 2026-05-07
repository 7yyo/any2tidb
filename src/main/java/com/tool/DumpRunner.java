package com.tool;

import com.tool.config.AppConfig;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.logging.Log;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.source.SourceDriver;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class DumpRunner {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SourceDriver driver;

    DumpRunner(AppConfig config, SchemaExtractor extractor, SourceDriver driver) {
        this.config = config;
        this.extractor = extractor;
        this.driver = driver;
    }

    void run(ApplicationArguments args, List<String> databases, List<String> tables) throws Exception {
        String taskName = App.resolveTaskName(args, driver.type(), "dump");
        MDC.put("task", taskName);

        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
        Log.info(log, "Connection info",
                "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                "mode", "dump");

        StepContext ctx = new StepContext();
        ctx.put("databases", databases != null ? databases : List.of());
        ctx.put("tables", tables != null ? tables : List.of());

        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.createInteractive(taskName, "dump", driver.type());
        try {
            meta.setArgs(java.util.Arrays.asList(args.getSourceArgs()));
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

            String outputDir = args.containsOption("output-dir")
                    ? args.getOptionValues("output-dir").get(0) : "dump-output";
            int fileSizeMb = App.parseIntOption(args, "file-size-mb", 256);
            int chunkSize = App.parseIntOption(args, "chunk-size", 200000);
            int concurrency = App.parseIntOption(args, "concurrency", 4);
            boolean noSnapshot = args.containsOption("no-snapshot");

            ctx.put("dumpOutputDir", outputDir);
            ctx.put("dumpFileSizeMb", fileSizeMb);
            ctx.put("dumpChunkSize", chunkSize);
            ctx.put("dumpConcurrency", concurrency);
            ctx.put("dumpNoSnapshot", noSnapshot);
            ctx.put("dumpOffsetStoragePath", ctx.get("offsetStoragePath", String.class));

            long threshold = fileSizeMb <= 0
                    ? Long.MAX_VALUE : (long) fileSizeMb * 1024 * 1024;

            List<MigrationStep> steps = new ArrayList<>();
            steps.add(new PreCheckStep(config, driver));
            steps.add(new DumpStep(config, extractor, driver.dumpExtractor(),
                    () -> new CsvDumpWriter(threshold), driver.consistencyProvider(), driver));

            StepResult result = new MigrationPipeline(steps).run(ctx);

            if (!result.isFatal()) {
                meta.markSuccess();
            } else {
                meta.markFailed(result.message());
            }
            taskManager.writeMeta(taskName, meta);

            App.handleResult(result, ctx);
        } catch (Exception e) {
            try {
                meta.markFailed(e.getMessage());
                taskManager.writeMeta(taskName, meta);
            } catch (Exception ignored) {}
            throw e;
        } finally {
            MDC.remove("task");
            taskManager.unlock();
        }
    }
}
