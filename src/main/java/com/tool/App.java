package com.tool;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.SqlServerDumpExtractor;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final SchemaVerifier verifier;
    private final SqlServerDumpExtractor dumpExtractor;

    public App(AppConfig config, SchemaExtractor extractor, SchemaConverter converter,
               SchemaWriter writer, SchemaVerifier verifier,
               SqlServerDumpExtractor dumpExtractor) {
        this.config        = config;
        this.extractor     = extractor;
        this.converter     = converter;
        this.writer        = writer;
        this.verifier      = verifier;
        this.dumpExtractor = dumpExtractor;
    }

    private void printBanner(boolean dryRun, boolean dumpMode, List<String> tablesOverride) {
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│  any2tidb  Any DB → TiDB Migration      │");
        System.out.println("├──────────┬──────────────────────────────┤");
        System.out.printf( "│  source  │  %-28s│%n",
                config.getSource().getHost() + ":" + config.getSource().getPort());
        System.out.printf( "│  target  │  %-28s│%n",
                config.getTarget().getHost() + ":" + config.getTarget().getPort());
        if (dryRun)
            System.out.println("│  mode    │  dry-run                     │");
        if (dumpMode)
            System.out.println("│  mode    │  dump (CSV export)           │");
        List<String> cfgSchemas = config.getConvert().getSchemas();
        List<String> cfgTables  = config.getConvert().getTables();
        if (cfgSchemas != null && !cfgSchemas.isEmpty())
            System.out.printf("│  schemas │  %-28s│%n", String.join(", ", cfgSchemas));
        if (cfgTables != null && !cfgTables.isEmpty())
            System.out.printf("│  tables  │  %-28s│%n", String.join(", ", cfgTables));
        if (tablesOverride != null && !tablesOverride.isEmpty())
            System.out.printf("│  tables* │  %-28s│%n", String.join(", ", tablesOverride));
        System.out.println("└──────────┴──────────────────────────────┘");
        System.out.println();
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun     = args.containsOption("dry-run");
        boolean schemaMode = args.containsOption("schema");
        boolean dumpMode   = args.containsOption("dump");

        if (!schemaMode && !dumpMode) {
            System.out.println("Usage: any2tidb --schema | --dump [--dry-run] [--tables=t1,t2]");
            return;
        }

        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(","))
                    .map(String::trim).toList();
        }

        printBanner(dryRun, dumpMode, tablesOverride);
        try (StructuredLogger log = StructuredLogger.open("any2tidb.log")) {
            log.log("INFO", "any2tidb started");

            ProgressReporter progress = new ProgressReporter();
            SummaryPrinter printer    = new SummaryPrinter();

            StepContext ctx = new StepContext();
            ctx.put("dryRun",         dryRun);
            ctx.put("tablesOverride", tablesOverride);

            List<MigrationStep> steps = new ArrayList<>();
            steps.add(new PreCheckStep(config));

            if (dumpMode) {
                steps.add(new DumpStep(config, extractor, dumpExtractor,
                        () -> new CsvDumpWriter(config.getDump().fileSizeThresholdBytes()),
                        log));
            } else {
                // schemaMode
                steps.add(new SchemaMigrateStep(config, extractor, converter, writer, log, progress));
                steps.add(new VerifyStep(config, verifier, log, printer));
            }

            StepResult result = new MigrationPipeline(steps).run(ctx);
            log.log("INFO", "any2tidb finished", "fatal", result.isFatal());

            Integer totalFailed = ctx.get("totalFailed", Integer.class);
            if (totalFailed != null && totalFailed > 0) System.exit(1);
            if (result.isFatal()) System.exit(1);
        }
    }
}
