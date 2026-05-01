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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import javax.sql.DataSource;
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

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, sourceDriver));
        steps.add(new SyncStep(config, targetDs, sourceDriver));

        StepResult result = new MigrationPipeline(steps).run(ctx);
        App.handleResult(result, ctx);
    }
}
