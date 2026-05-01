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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

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

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, driver));

        if (dumpMode) {
            boolean useSnapshot = parseConsistency(args);
            ctx.put("dumpSnapshot", useSnapshot);

            String outputDir = args.containsOption("output-dir")
                    ? args.getOptionValues("output-dir").get(0) : "dump-output";
            int fileSizeMb = App.parseIntOption(args, "file-size-mb", 256);
            int chunkSize = App.parseIntOption(args, "chunk-size", 200000);
            int concurrency = App.parseIntOption(args, "concurrency", 4);

            ctx.put("dumpOutputDir", outputDir);
            ctx.put("dumpFileSizeMb", fileSizeMb);
            ctx.put("dumpChunkSize", chunkSize);
            ctx.put("dumpConcurrency", concurrency);

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
    }

    private boolean parseConsistency(ApplicationArguments args) {
        if (!args.containsOption("consistency")) return false;
        String v = args.getOptionValues("consistency").get(0).toLowerCase();
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        Logger log = LoggerFactory.getLogger(App.class);
        Log.error(log, "Invalid --consistency value: " + v + " — valid: true, false");
        throw new IllegalArgumentException("Invalid --consistency: " + v);
    }
}
