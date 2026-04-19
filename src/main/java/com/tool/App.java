package com.tool;

import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.pipeline.steps.SchemaMigrateStep;
import com.tool.pipeline.steps.VerifyStep;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SqlServerExtractor extractor;
    private final SchemaConverter converter;
    private final TiDBWriter writer;
    private final SchemaVerifier verifier;

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter,
               TiDBWriter writer, SchemaVerifier verifier) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.verifier  = verifier;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = args.containsOption("dry-run");

        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(","))
                    .map(String::trim).toList();
        }

        // Banner
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│  ms2tidb  SQL Server → TiDB Migration   │");
        System.out.println("├──────────┬──────────────────────────────┤");
        System.out.printf( "│  source  │  %-28s│%n",
                config.getSource().getHost() + ":" + config.getSource().getPort());
        System.out.printf( "│  target  │  %-28s│%n",
                config.getTarget().getHost() + ":" + config.getTarget().getPort());
        if (dryRun)
            System.out.println("│  mode    │  dry-run                     │");
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

        try (StructuredLogger log = StructuredLogger.open("ms2tidb.log")) {
            log.log("INFO", "ms2tidb started");

            ProgressReporter progress = new ProgressReporter();
            SummaryPrinter printer    = new SummaryPrinter();

            StepContext ctx = new StepContext();
            ctx.put("dryRun",         dryRun);
            ctx.put("tablesOverride", tablesOverride);

            List<MigrationStep> steps = List.of(
                    new PreCheckStep(config),
                    new SchemaMigrateStep(config, extractor, converter, writer, log, progress),
                    new VerifyStep(config, verifier, log, printer)
            );

            StepResult result = new MigrationPipeline(steps).run(ctx);
            log.log("INFO", "ms2tidb finished", "fatal", result.isFatal());

            Integer totalFailed = ctx.get("totalFailed", Integer.class);
            if (totalFailed != null && totalFailed > 0) System.exit(1);
            if (result.isFatal()) System.exit(1);
        }
    }
}
