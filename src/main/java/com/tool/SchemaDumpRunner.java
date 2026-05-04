package com.tool;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.Log;
import com.tool.output.ProgressReporter;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
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
import com.tool.task.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

class SchemaDumpRunner {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final SchemaVerifier verifier;
    private final SourceDriver driver;

    SchemaDumpRunner(AppConfig config, SchemaExtractor extractor, SchemaConverter converter,
                     SchemaWriter writer, SchemaVerifier verifier, SourceDriver driver) {
        this.config = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer = writer;
        this.verifier = verifier;
        this.driver = driver;
    }

    void run(ApplicationArguments args, String mode,
             List<String> databases, List<String> tables,
             boolean dryRun, boolean dropIfExists, boolean continueOnError) throws Exception {
        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
        Log.info(log, "Connection info",
                "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                "mode", mode);

        boolean dumpMode = "dump".equals(mode);

        StepContext ctx = new StepContext();
        ctx.put("dryRun", dryRun);
        ctx.put("databases", databases != null ? databases : List.of());
        ctx.put("tables", tables != null ? tables : List.of());
        ctx.put("dropIfExists", dropIfExists);
        ctx.put("continueOnError", continueOnError);

        // Task support — mandatory
        if (!args.containsOption("task")) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta;
        if (Files.exists(taskManager.getTaskDir(taskName))) {
            meta = taskManager.resume(taskName);
        } else {
            meta = taskManager.create(taskName, "sqlserver");
            if (databases != null && !databases.isEmpty()) {
                meta.setDatabases(new ArrayList<>(databases));
            }
            // Set source/target from config
            TaskMeta.SourceInfo src = new TaskMeta.SourceInfo();
            src.setType("sqlserver");
            src.setHost(config.getSource().getHost());
            src.setPort(config.getSource().getPort());
            src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
            meta.setSource(src);
            TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
            tgt.setType("tidb");
            tgt.setHost(config.getTarget().getHost());
            tgt.setPort(config.getTarget().getPort());
            tgt.setDatabase("");
            meta.setTarget(tgt);
            taskManager.writeMeta(taskName, meta);
        }
        App.resolveTaskPaths(taskName, taskManager, ctx);
        taskManager.transition(taskName, TaskState.DUMPING);
        ctx.put("taskManager", taskManager);
        ctx.put("taskName", taskName);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, driver));

        if (dumpMode) {
            String outputDir = args.containsOption("output-dir")
                    ? args.getOptionValues("output-dir").get(0) : "dump-output";
            int fileSizeMb = App.parseIntOption(args, "file-size-mb", 256);
            int chunkSize = App.parseIntOption(args, "chunk-size", 200000);
            int concurrency = App.parseIntOption(args, "concurrency", 4);
            String offsetStoragePath = args.containsOption("offset-storage-path")
                    ? args.getOptionValues("offset-storage-path").get(0) : null;

            ctx.put("dumpOutputDir", outputDir);
            ctx.put("dumpFileSizeMb", fileSizeMb);
            ctx.put("dumpChunkSize", chunkSize);
            ctx.put("dumpConcurrency", concurrency);
            if (offsetStoragePath != null) ctx.put("dumpOffsetStoragePath", offsetStoragePath);

            long threshold = fileSizeMb <= 0
                    ? Long.MAX_VALUE : (long) fileSizeMb * 1024 * 1024;
            steps.add(new DumpStep(config, extractor, driver.dumpExtractor(),
                    () -> new CsvDumpWriter(threshold), driver.consistencyProvider(), driver));
        } else {
            ProgressReporter progress = new ProgressReporter();
            steps.add(new SchemaMigrateStep(config, extractor, converter, writer, progress, driver));
            steps.add(new VerifyStep(config, verifier, driver));
        }

        StepResult result = new MigrationPipeline(steps).run(ctx);
        App.handleResult(result, ctx);

        try {
            if (!result.isFatal()) {
                taskManager.transition(taskName, TaskState.DUMPED);
            } else {
                taskManager.fail(taskName, result.message());
            }
        } finally {
            taskManager.unlock();
        }
    }

}
