package com.tool;

import com.tool.cli.DumpCommand;
import com.tool.cli.SchemaCommand;
import com.tool.cli.SnapshotCommand;
import com.tool.common.source.SourceCatalog;
import com.tool.config.AppConfig;
import com.tool.dump.extractor.SqlServerDumpExtractor;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.snapshot.SnapshotStep;
import com.tool.pipeline.steps.SchemaMigrateStep;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.writer.SchemaWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static Object parsedCommand;
    private static String[] rawArgs;

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final SchemaVerifier verifier;
    private final SqlServerDumpExtractor dumpExtractor;
    private final DataSource sourceDs;
    private final DataSource targetDs;

    public App(AppConfig config, SchemaExtractor extractor, SchemaConverter converter,
               SchemaWriter writer, SchemaVerifier verifier,
               SqlServerDumpExtractor dumpExtractor,
               @org.springframework.beans.factory.annotation.Qualifier("sourceDataSource") DataSource sourceDs,
               @org.springframework.beans.factory.annotation.Qualifier("targetDataSource") DataSource targetDs) {
        this.config        = config;
        this.extractor     = extractor;
        this.converter     = converter;
        this.writer        = writer;
        this.verifier      = verifier;
        this.dumpExtractor = dumpExtractor;
        this.sourceDs      = sourceDs;
        this.targetDs      = targetDs;
    }

    @Command(name = "any2tidb", mixinStandardHelpOptions = true,
            subcommands = {SchemaCommand.class, DumpCommand.class, SnapshotCommand.class})
    static class TopLevelCommand implements Runnable {
        @Override
        public void run() {}
    }

    private static void printHelp() {
        System.out.println(
"""
any2tidb — SQL Server to TiDB Migration Tool

Usage:
  any2tidb schema [options]
  any2tidb dump   [options]
  any2tidb --help, -h

Commands:
  schema    Migrate schema (CREATE TABLE + INDEX) to TiDB
  dump      Export data as CSV files
  snapshot  Export data directly to TiDB via Debezium CDC

Run 'any2tidb schema --help', 'any2tidb dump --help', or 'any2tidb snapshot --help' for command-specific options.

Config:
  conf/config.yml     Source/target connection
  logs/any2tidb.log   Structured log output
""");
    }

    private static void printSchemaHelp() {
        System.out.println(
"""
any2tidb schema — Migrate schema to TiDB

Usage:
  any2tidb schema [options]

Options:
  --dry                  Preview DDL without writing to TiDB
  --tables=t1,t2,...     Only process specified tables (default: all)
  --dbs=db1,db2          Only process specified databases (default: all)
  --drop                 Drop existing tables before creating
  --concurrency=N        Number of databases to migrate in parallel (default: 1)
  --help, -h             Show this help message

Examples:
  any2tidb schema --dry
  any2tidb schema --dbs=Authentication,Entity --dry
  any2tidb schema --tables=User,Account
""");
    }

    private static void printDumpHelp() {
        System.out.println(
"""
any2tidb dump — Export data as CSV files

Usage:
  any2tidb dump [options]

Options:
  --tables=t1,t2,...     Only process specified tables (default: all)
  --dbs=db1,db2          Only process specified databases (default: all)
  -o DIR                 Output directory (default: output/dump)
  --concurrency=N        Parallel table export (default: 4)
  --chunk=N              Rows per fetch (default: 10000)
  --roll=N               Max CSV file size before rolling (MB, default: 256)
  --nolock               Use WITH (NOLOCK) on SELECT queries (default: true)
  --help, -h             Show this help message

Examples:
  any2tidb dump
  any2tidb dump --dbs=Authentication --tables=User
  any2tidb dump -o /tmp/dump --concurrency=8
""");
    }

    private static void printSnapshotHelp() {
        System.out.println(
"""
any2tidb snapshot -- Export data directly to TiDB via Debezium CDC

Usage:
  any2tidb snapshot [options]

Options:
  --tables=t1,t2,...       Only process specified tables (default: all)
  --dbs=db1,db2            Only process specified databases (default: all)
  --batch-size=N           INSERT batch size (default: 5000)
  --fetch-size=N           Debezium snapshot fetch size (default: 2000)
  --snapshot-threads=N     Debezium parallel chunk threads (default: 1)
  --auto-enable-cdc        Automatically enable CDC on SQL Server
  --help, -h               Show this help message

Prerequisites:
  SQL Server Agent must be running
  CDC must be enabled on target databases and tables
  Run 'any2tidb schema' first to create TiDB tables

Examples:
  any2tidb snapshot
  any2tidb snapshot --dbs=Authentication --tables=User
  any2tidb snapshot --auto-enable-cdc --snapshot-threads=4
""");
    }

    public static void main(String[] args) {
        rawArgs = args;
        boolean hasHelp = Arrays.stream(args).anyMatch(a -> "--help".equals(a) || "-h".equals(a));

        TopLevelCommand top = new TopLevelCommand();
        CommandLine cmd = new CommandLine(top);

        // Show custom help, no Spring context needed
        if (hasHelp) {
            String sub = args.length > 0 && !args[0].startsWith("-") ? args[0] : null;
            if ("schema".equals(sub)) { printSchemaHelp(); System.exit(0); }
            if ("dump".equals(sub))   { printDumpHelp();   System.exit(0); }
            if ("snapshot".equals(sub)){ printSnapshotHelp(); System.exit(0); }
            printHelp();
            System.exit(0);
        }

        // Parse for validation (unknown flags, type errors) — no help output
        try {
            cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            String sub = args.length > 0 && !args[0].startsWith("-") ? args[0] : null;
            System.err.println("\033[1;31m" + e.getMessage() + "\033[0m");
            if ("schema".equals(sub)) printSchemaHelp();
            else if ("dump".equals(sub)) printDumpHelp();
            else if ("snapshot".equals(sub)) printSnapshotHelp();
            else printHelp();
            System.exit(1);
        }

        CommandLine.ParseResult pr = cmd.getParseResult();
        CommandLine.ParseResult subResult = pr.subcommand();
        if (subResult == null) {
            printHelp();
            System.exit(0);
        }

        parsedCommand = subResult.commandSpec().userObject();

        SpringApplication app = new SpringApplication(App.class);
        app.setBanner((env, sourceClass, out) -> {});
        System.exit(SpringApplication.exit(app.run(rawArgs)));
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        ProgressReporter progress = new ProgressReporter();
        SummaryPrinter printer    = new SummaryPrinter();

        if (parsedCommand instanceof SchemaCommand schema) {
            runSchema(schema, progress, printer);
        } else if (parsedCommand instanceof DumpCommand dump) {
            runDump(dump, progress);
        } else if (parsedCommand instanceof SnapshotCommand snapshot) {
            runSnapshot(snapshot, progress);
        }
    }

    private void runSchema(SchemaCommand schema, ProgressReporter progress, SummaryPrinter printer) throws Exception {
        String command = "schema";
        printBanner(command, schema.dryRun, schema.databases, schema.tables, schema.dropIfExists, schema.concurrency);
        log.info("[\"any2tidb started\"] [command={}] [dryRun={}]", command, schema.dryRun);

        StepContext ctx = new StepContext();
        ctx.put("dryRun",           schema.dryRun);
        ctx.put("tablesOverride",   schema.tables);
        ctx.put("databasesOverride", schema.databases);
        ctx.put("dropIfExists",     schema.dropIfExists);
        ctx.put("continueOnError",  true);
        ctx.put("concurrency",      schema.concurrency);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, true));
        steps.add(new SchemaMigrateStep(config, extractor, converter, writer, progress, printer, verifier, sourceDs, targetDs));

        executePipeline(ctx, steps, command);
    }

    private void runDump(DumpCommand dump, ProgressReporter progress) throws Exception {
        String command = "dump";
        printBanner(command, false, dump.databases, dump.tables, dump.concurrency, dump.outputDir, dump.chunkSize, dump.fileSizeMb, dump.nolock);
        log.info("[\"any2tidb started\"] [command={}]", command);

        StepContext ctx = new StepContext();
        ctx.put("tablesOverride",   dump.tables);
        ctx.put("databasesOverride", dump.databases);
        ctx.put("outputDir",        dump.outputDir);
        ctx.put("concurrency",      dump.concurrency);
        ctx.put("chunkSize",        dump.chunkSize);
        ctx.put("fileSizeMb",       dump.fileSizeMb);
        ctx.put("nolock",           dump.nolock);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, false));
        long sizeThreshold = dump.fileSizeMb * 1024L * 1024L;
        steps.add(new DumpStep(config, (SourceCatalog) extractor, dumpExtractor,
                () -> new CsvDumpWriter(sizeThreshold), progress));

        executePipeline(ctx, steps, command);
    }

    private void runSnapshot(SnapshotCommand snapshot, ProgressReporter progress) throws Exception {
        String command = "snapshot";
        System.out.println();
        System.out.printf("any2tidb %s | %s → %s%n",
                command, connInfo(config.getSource()), connInfo(config.getTarget()));
        System.out.println(SEP);

        java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
        if (snapshot.databases != null && !snapshot.databases.isEmpty())
            filters.put("databases", String.join(", ", snapshot.databases));
        if (snapshot.tables != null && !snapshot.tables.isEmpty())
            filters.put("tables", String.join(", ", snapshot.tables));
        if (snapshot.batchSize != 5000)
            filters.put("batch-size", String.valueOf(snapshot.batchSize));
        if (snapshot.fetchSize != 2000)
            filters.put("fetch-size", String.valueOf(snapshot.fetchSize));
        if (snapshot.snapshotThreads != 1)
            filters.put("threads", String.valueOf(snapshot.snapshotThreads));
        if (snapshot.autoEnableCdc)
            filters.put("auto-enable-cdc", "true");
        printFilters(filters);

        System.out.println();
        System.out.println("── Snapshot " + SEP.substring(11));
        System.out.println();

        log.info("[\"any2tidb started\"] [command={}]", command);

        StepContext ctx = new StepContext();
        ctx.put("tablesOverride",   snapshot.tables);
        ctx.put("databasesOverride", snapshot.databases);
        ctx.put("batchSize",        snapshot.batchSize);
        ctx.put("fetchSize",        snapshot.fetchSize);
        ctx.put("snapshotThreads",  snapshot.snapshotThreads);
        ctx.put("autoEnableCdc",    snapshot.autoEnableCdc);

        List<MigrationStep> steps = new ArrayList<>();
        steps.add(new PreCheckStep(config, false));
        steps.add(new SnapshotStep(config, targetDs));

        executePipeline(ctx, steps, command);
    }

    private void executePipeline(StepContext ctx, List<MigrationStep> steps, String command) {
        StepResult result;
        try {
            result = new MigrationPipeline(steps).run(ctx);
        } catch (Exception e) {
            log.error("[\"pipeline failed\"] [error=\"{}\"]", e.getMessage());
            System.err.println("[FATAL] " + e.getMessage());
            result = StepResult.fatal(e.getMessage());
        }

        log.info("[\"any2tidb finished\"] [fatal={}] [message=\"{}\"]", result.isFatal(), result.message());
        if (result.isFatal() && !Boolean.TRUE.equals(ctx.get("preCheckFailed", Boolean.class))) {
            System.err.println("[ERROR] " + result.message());
        }

        Integer totalFailed = ctx.get("totalFailed", Integer.class);
        if ((totalFailed != null && totalFailed > 0) || result.isFatal()) {
            System.exit(1);
        }
    }

    private String connInfo(AppConfig.DbConfig db) {
        return db.getHost() + ":" + db.getPort();
    }

    private static final String SEP = "─────────────────────────────────────────────────────────────────";

    private void printBanner(String command, boolean dryRun,
                             List<String> databases, List<String> tables,
                             boolean dropIfExists, int concurrency) {
        System.out.println();
        System.out.printf("any2tidb %s | %s → %s%n",
                command, connInfo(config.getSource()), connInfo(config.getTarget()));
        System.out.println(SEP);

        java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
        if (databases != null && !databases.isEmpty())
            filters.put("databases", String.join(", ", databases));
        if (tables != null && !tables.isEmpty())
            filters.put("tables", String.join(", ", tables));
        if (dryRun)
            filters.put("mode", "dry-run");
        if (dropIfExists)
            filters.put("drop", "true");
        if (concurrency > 1)
            filters.put("concurrency", String.valueOf(concurrency));
        printFilters(filters);

        System.out.println();
        System.out.println("── Migration " + SEP.substring(12));
        System.out.println();
    }

    private void printBanner(String command, boolean dryRun,
                             List<String> databases, List<String> tables,
                             int concurrency, String outputDir,
                             int chunkSize, int fileSizeMb, boolean nolock) {
        System.out.println();
        System.out.printf("any2tidb %s | %s → %s%n",
                command, connInfo(config.getSource()), connInfo(config.getTarget()));
        System.out.println(SEP);

        java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
        if (databases != null && !databases.isEmpty())
            filters.put("databases", String.join(", ", databases));
        if (tables != null && !tables.isEmpty())
            filters.put("tables", String.join(", ", tables));
        if (concurrency != 4)
            filters.put("concurrency", String.valueOf(concurrency));
        if (outputDir != null && !outputDir.isEmpty())
            filters.put("output", outputDir);
        if (chunkSize != 10000)
            filters.put("chunk", String.valueOf(chunkSize));
        if (fileSizeMb != 256)
            filters.put("roll", fileSizeMb + " MB");
        if (!nolock)
            filters.put("nolock", "false");
        printFilters(filters);

        System.out.println();
        System.out.println("── Dump " + SEP.substring(8));
        System.out.println();
    }

    private void printFilters(java.util.LinkedHashMap<String, String> filters) {
        if (filters.isEmpty()) return;
        int keyWidth = filters.keySet().stream().mapToInt(String::length).max().orElse(0);
        for (var entry : filters.entrySet()) {
            System.out.printf("  %-" + keyWidth + "s  %s%n", entry.getKey(), entry.getValue());
        }
    }
}
