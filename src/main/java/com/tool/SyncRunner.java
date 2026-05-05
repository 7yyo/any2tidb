package com.tool;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.source.SourceDriver;
import com.tool.sync.SyncConfig;
import com.tool.sync.SyncStep;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class SyncRunner {

    private final AppConfig config;
    private final DataSource targetDs;
    private final SourceDriver sourceDriver;

    SyncRunner(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
        this.config = config;
        this.targetDs = targetDs;
        this.sourceDriver = sourceDriver;
    }

    void run(ApplicationArguments args) throws Exception {
        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
        Log.info(log, "Connection info",
                "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                "mode", "sync");

        int pollIntervalMs = App.parseIntOption(args, "poll-interval-ms", SyncConfig.DEFAULT_POLL_INTERVAL_MS);
        String offsetStoragePath = args.containsOption("offset-storage-path")
                ? args.getOptionValues("offset-storage-path").get(0) : null;
        String schemaHistoryPath = args.containsOption("schema-history-path")
                ? args.getOptionValues("schema-history-path").get(0) : null;
        String metaFile = args.containsOption("meta-file")
                ? args.getOptionValues("meta-file").get(0) : SyncConfig.DEFAULT_META_FILE;

        SyncConfig syncConfig = SyncConfig.defaults()
                .withPollIntervalMs(pollIntervalMs)
                .withMetaFile(metaFile);
        if (offsetStoragePath != null) syncConfig = syncConfig.withOffsetStoragePath(offsetStoragePath);
        if (schemaHistoryPath != null) syncConfig = syncConfig.withSchemaHistoryPath(schemaHistoryPath);

        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("syncConfig", syncConfig);

        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }

        // ── from-task: validate BEFORE creating the sync task ──
        if (!args.containsOption("from-task") || args.getOptionValues("from-task").isEmpty()) {
            throw new IllegalArgumentException("--from-task=NAME is required for sync mode");
        }
        String fromTask = args.getOptionValues("from-task").get(0);
        if (fromTask == null || fromTask.isBlank()) {
            throw new IllegalArgumentException("--from-task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        Path fromDir = taskManager.getTaskDir(fromTask);
        if (!java.nio.file.Files.exists(fromDir)) {
            throw new IllegalArgumentException("--from-task '" + fromTask + "' not found");
        }
        TaskMeta parent = taskManager.status(fromTask);
        String parentMode = parent.getMode();
        if (!"snapshot".equals(parentMode) && !"dump".equals(parentMode)) {
            throw new IllegalArgumentException("--from-task '" + fromTask
                    + "' is mode=" + parentMode + ", expected snapshot or dump");
        }
        if (!"SUCCESS".equals(parent.getStatus())) {
            Log.warn(log, "--from-task '" + fromTask + "' status is " + parent.getStatus()
                    + ", offsets may be incomplete");
        }

        TaskMeta meta = taskManager.createInteractive(taskName, "sync", "sqlserver");
        meta.setFromTask(fromTask);
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase("");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);

        ctx.put("offsetStoragePath", fromDir.resolve("offsets").toString());
        ctx.put("schemaHistoryPath", fromDir.resolve("history").toString());

        taskManager.writeMeta(taskName, meta);

        String taskOffsetPath = ctx.get("offsetStoragePath", String.class);
        String taskHistoryPath = ctx.get("schemaHistoryPath", String.class);
        if (taskOffsetPath != null) {
            syncConfig = syncConfig.withOffsetStoragePath(taskOffsetPath);
        }
        if (taskHistoryPath != null) {
            syncConfig = syncConfig.withSchemaHistoryPath(taskHistoryPath);
        }
        ctx.put("syncConfig", syncConfig);
        ctx.put("taskManager", taskManager);
        ctx.put("taskName", taskName);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, sourceDriver));
        steps.add(new SyncStep(config, targetDs, sourceDriver));

        StepResult result = new MigrationPipeline(steps).run(ctx);
        App.handleResult(result, ctx);

        try {
            if (result.isFatal()) {
                meta.markFailed(result.message());
            } else {
                meta.markSuccess();
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
    }
}
