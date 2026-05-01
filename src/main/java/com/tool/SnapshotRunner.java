package com.tool;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.snapshot.SnapshotConfig;
import com.tool.snapshot.SnapshotStep;
import com.tool.source.SourceDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

class SnapshotRunner {

    private final AppConfig config;
    private final DataSource targetDs;
    private final SourceDriver sourceDriver;

    SnapshotRunner(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
        this.config = config;
        this.targetDs = targetDs;
        this.sourceDriver = sourceDriver;
    }

    void run(ApplicationArguments args, List<String> databases, List<String> tables) throws Exception {
        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
        Log.info(log, "Connection info",
                "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                "mode", "snapshot");

        int batchSize = App.parseIntOption(args, "batch-size", SnapshotConfig.DEFAULT_BATCH_INSERT_SIZE);
        int fetchSize = App.parseIntOption(args, "fetch-size", SnapshotConfig.DEFAULT_SNAPSHOT_FETCH_SIZE);
        int snapshotThreads = App.parseIntOption(args, "snapshot-threads", SnapshotConfig.DEFAULT_SNAPSHOT_MAX_THREADS);
        boolean enableCdc = args.containsOption("enable-cdc");
        String offsetStoragePath = args.containsOption("offset-storage-path")
                ? args.getOptionValues("offset-storage-path").get(0) : null;
        String schemaHistoryPath = args.containsOption("schema-history-path")
                ? args.getOptionValues("schema-history-path").get(0) : null;
        int maxQueueSize = App.parseIntOption(args, "max-queue-size", SnapshotConfig.DEFAULT_MAX_QUEUE_SIZE);
        int pollIntervalMs = App.parseIntOption(args, "poll-interval-ms", SnapshotConfig.DEFAULT_POLL_INTERVAL_MS);
        int offsetCommitIntervalMs = App.parseIntOption(args, "offset-commit-interval-ms", SnapshotConfig.DEFAULT_OFFSET_COMMIT_INTERVAL_MS);

        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tables", tables != null ? tables : List.of());
        ctx.put("databases", databases != null ? databases : List.of());
        ctx.put("batchSize", batchSize);
        ctx.put("fetchSize", fetchSize);
        ctx.put("snapshotThreads", snapshotThreads);
        ctx.put("enableCdc", enableCdc);
        ctx.put("maxQueueSize", maxQueueSize);
        ctx.put("pollIntervalMs", pollIntervalMs);
        ctx.put("offsetCommitIntervalMs", offsetCommitIntervalMs);
        if (offsetStoragePath != null) ctx.put("offsetStoragePath", offsetStoragePath);
        if (schemaHistoryPath != null) ctx.put("schemaHistoryPath", schemaHistoryPath);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config));
        steps.add(new SnapshotStep(config, targetDs, sourceDriver));

        StepResult result = new MigrationPipeline(steps).run(ctx);
        App.handleResult(result, ctx);
    }
}
