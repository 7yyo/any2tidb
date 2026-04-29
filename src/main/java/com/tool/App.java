package com.tool;

import com.tool.config.AppConfig;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;

import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.DumpStep;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.pipeline.steps.SchemaMigrateStep;
import com.tool.pipeline.steps.VerifyStep;
import com.tool.snapshot.SnapshotConfig;
import com.tool.snapshot.SnapshotStep;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.writer.SchemaWriter;
import com.tool.source.SourceDriver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final SchemaVerifier verifier;
    private final SourceDriver driver;

    public App(AppConfig config, SchemaExtractor extractor, SchemaConverter converter,
               SchemaWriter writer, SchemaVerifier verifier,
               SourceDriver driver) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.verifier  = verifier;
        this.driver    = driver;
    }

    public static void main(String[] args) {
        boolean wantHelp = Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h");

        if (args.length == 0 || wantHelp) {
            List<String> pos = positionalArgs(args);
            if (pos.size() >= 2) {
                String source = pos.get(0);
                String mode   = pos.get(1);
                if (!SOURCES.contains(source)) {
                    System.out.println("Error: unknown source '" + source + "'. Valid: " + SOURCES);
                } else if (!MODES.contains(mode)) {
                    System.out.println("Error: unknown mode '" + mode + "'. Valid: " + MODES);
                } else {
                    printUsage(source, mode);
                }
            } else if (pos.size() == 1) {
                String arg = pos.get(0);
                if (SOURCES.contains(arg)) {
                    printUsage(arg);
                } else if (MODES.contains(arg)) {
                    System.out.println("Error: source is required. Usage: any2tidb <source> " + arg);
                } else {
                    System.out.println("Error: unknown argument '" + arg + "'");
                    System.out.println("Run 'any2tidb --help' for usage.");
                }
            } else {
                printUsage();
            }
            return;
        }
        if (Arrays.asList(args).contains("--version")) {
            System.out.println("any2tidb 1.0.0");
            return;
        }
        SpringApplication app = new SpringApplication(App.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.run(args);
    }

    private static final Set<String> SOURCES = Set.of("sqlserver");
    private static final Set<String> MODES   = Set.of("schema", "dump", "snapshot");

    /**
     * Parse positional args: returns [source, mode] or [source] or [mode] or empty.
     */
    private static List<String> positionalArgs(String[] args) {
        List<String> out = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--") || (arg.startsWith("-") && !arg.startsWith("--"))) continue;
            out.add(arg);
        }
        return out;
    }

    private static void printUsage() {
        System.out.println("any2tidb — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: any2tidb <source> <mode> [options]");
        System.out.println();
        System.out.println("Sources:");
        for (String s : SOURCES) System.out.println("  " + s);
        System.out.println();
        System.out.println("Modes:");
        System.out.println("  schema        Migrate schema (DDL) only");
        System.out.println("  dump          Export data as CSV (Dumpling format)");
        System.out.println("  snapshot      Stream full data directly to TiDB via Debezium CDC");
        System.out.println();
        System.out.println("Run 'any2tidb <source> --help' for source-specific options.");
        System.out.println("Run 'any2tidb <source> <mode> --help' for mode-specific options.");
        System.out.println();
        System.out.println("Other:");
        System.out.println("  --help, -h    Show this help");
        System.out.println("  --version     Print version");
    }

    private static void printUsage(String source) {
        System.out.println("any2tidb " + source + " — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: any2tidb " + source + " <mode> [options]");
        System.out.println();
        System.out.println("Modes:");
        System.out.println("  schema        Migrate schema (DDL) only");
        System.out.println("  dump          Export data as CSV (Dumpling format)");
        System.out.println("  snapshot      Stream full data directly to TiDB via Debezium CDC");
        System.out.println();
        if ("sqlserver".equals(source)) {
            System.out.println("SQL Server options (common across modes):");
            System.out.println("  --enable-cdc     Automatically enable CDC on source database");
        }
        System.out.println();
        System.out.println("Run 'any2tidb " + source + " <mode> --help' for mode-specific options.");
    }

    private static void printUsage(String source, String mode) {
        System.out.println("any2tidb " + source + " " + mode + " — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: any2tidb " + source + " " + mode + " [options]");
        System.out.println();
        System.out.println("Options:");
        if ("schema".equals(mode) || "dump".equals(mode)) {
            System.out.println("  --dry-run             Print DDL / SQL without executing");
            System.out.println("  --databases=db1,db2   Filter databases to migrate");
            System.out.println("  --tables=t1,t2        Migrate only specified tables");
            System.out.println("  --drop-if-exists      DROP existing tables before creating");
            System.out.println("  --stop-on-error       Stop on first table failure (default: continue)");
        }
        if ("dump".equals(mode)) {
            System.out.println("  --output-dir=PATH     Output directory (default: dump-output)");
            System.out.println("  --file-size-mb=N      Max CSV file size in MB (default: 256, 0=unlimited)");
            System.out.println("  --chunk-size=N        Rows per PK-range chunk (default: 200000)");
            System.out.println("  --consistency=true    Enable database-level snapshot consistency (default: false)");
            System.out.println("  --concurrency=N       Number of concurrent table exports (default: 4)");
        }
        if ("snapshot".equals(mode)) {
            System.out.println("  --databases=db1,db2   Only process specified databases (default: all)");
            System.out.println("  --tables=t1,t2        Only process specified tables");
            System.out.println("  --batch-size=N        INSERT batch size (default: 10000)");
            System.out.println("  --fetch-size=N        Debezium snapshot fetch size (default: 10000)");
            System.out.println("  --snapshot-threads=N  Parallel chunk threads (default: 1)");
            System.out.println("  --offset-storage-path=PATH  Debezium offset file dir (default: snapshot-offsets)");
            System.out.println("  --schema-history-path=PATH  Debezium schema history dir (default: snapshot-schema-history)");
            System.out.println("  --max-queue-size=N    Debezium engine max queue size (default: 16384)");
            System.out.println("  --poll-interval-ms=N  Debezium poll interval in ms (default: 500)");
            System.out.println("  --offset-commit-interval-ms=N  Offset flush interval in ms (default: 10000)");
            System.out.println("  --snapshot-max-threads-multiplier=N  Thread multiplier (default: 1.0)");
            System.out.println("  --enable-cdc         Automatically enable CDC on source database and tables");
        }
        System.out.println();
        System.out.println("Configuration: application.yml in working directory (source/target connection only)");
    }

    private static List<String> parseListOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) return null;
        List<String> values = args.getOptionValues(name);
        if (values.isEmpty()) return null;
        String val = values.get(0);
        if (val == null || val.isBlank()) return null;
        return Arrays.stream(val.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static int parseIntOption(ApplicationArguments args, String name, int defaultVal) {
        if (!args.containsOption(name)) return defaultVal;
        List<String> values = args.getOptionValues(name);
        if (values.isEmpty()) return defaultVal;
        try { return Integer.parseInt(values.get(0)); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    // ── Flag whitelists ─────────────────────────────────────────────────────
    private static final Set<String> COMMON_FLAGS = Set.of(
            "dry-run", "databases", "tables", "drop-if-exists", "stop-on-error"
    );
    private static final Set<String> DUMP_FLAGS = Set.of(
            "output-dir", "file-size-mb", "chunk-size", "consistency", "concurrency"
    );
    private static final Set<String> SNAPSHOT_FLAGS = Set.of(
            "batch-size", "fetch-size", "snapshot-threads",
            "offset-storage-path", "schema-history-path",
            "max-queue-size", "poll-interval-ms", "offset-commit-interval-ms",
            "snapshot-max-threads-multiplier", "enable-cdc"
    );

    private Set<String> knownFlags(String source, String mode) {
        Set<String> s = new java.util.LinkedHashSet<>(COMMON_FLAGS);
        if ("dump".equals(mode))     s.addAll(DUMP_FLAGS);
        if ("snapshot".equals(mode)) s.addAll(SNAPSHOT_FLAGS);
        s.addAll(driver.ownFlags());
        return s;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> pos = args.getNonOptionArgs();
        if (pos.size() < 2) {
            System.out.println("Usage: any2tidb <source> <mode> [options]");
            return;
        }
        String source = pos.get(0);
        String mode   = pos.get(1);

        if (!SOURCES.contains(source)) {
            System.out.println("Error: unknown source '" + source + "'. Valid: " + SOURCES);
            return;
        }
        if (!MODES.contains(mode)) {
            System.out.println("Error: unknown mode '" + mode + "'. Valid: " + MODES);
            return;
        }

        Set<String> allowed = knownFlags(source, mode);
        for (String name : args.getOptionNames()) {
            if (!allowed.contains(name)) {
                System.out.println("Error: unknown option --" + name + " for " + source + " " + mode);
                System.out.println("Run 'any2tidb " + source + " " + mode + " --help' for usage.");
                return;
            }
        }

        boolean dryRun         = args.containsOption("dry-run");
        boolean schemaMode     = "schema".equals(mode);
        boolean dumpMode       = "dump".equals(mode);
        boolean snapshotMode   = "snapshot".equals(mode);

        // Parse shared CLI flags
        List<String> databases     = parseListOption(args, "databases");
        List<String> tables        = parseListOption(args, "tables");
        boolean dropIfExists       = args.containsOption("drop-if-exists");
        boolean continueOnError    = !args.containsOption("stop-on-error");

        if (snapshotMode) {
            runSnapshot(args, databases, tables);
            return;
        }

        try (StructuredLogger log = StructuredLogger.open("any2tidb.log")) {
            log.log("INFO", "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
            log.log("INFO", "Connection info",
                    "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                    "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                    "mode", mode);

            ProgressReporter progress = new ProgressReporter();

            StepContext ctx = new StepContext();
            ctx.put("dryRun",             dryRun);
            ctx.put("databases",          databases != null ? databases : List.of());
            ctx.put("tables",             tables   != null ? tables   : List.of());
            ctx.put("dropIfExists",        dropIfExists);
            ctx.put("continueOnError",     continueOnError);

            // Parse consistency flag early for PreCheck edition check
            boolean useSnapshot = false;
            if (dumpMode && args.containsOption("consistency")) {
                String v = args.getOptionValues("consistency").get(0).toLowerCase();
                if ("true".equals(v)) useSnapshot = true;
                else if (!"false".equals(v)) {
                    log.log("ERROR", "Invalid --consistency value: " + v
                            + " — valid: true, false");
                    throw new IllegalArgumentException("Invalid --consistency: " + v);
                }
            }
            ctx.put("dumpSnapshot", useSnapshot);

            List<MigrationStep> steps = new ArrayList<>();
            steps.add(new PreCheckStep(config, driver.consistencyProvider()));

            if (dumpMode) {
                String outputDir          = args.containsOption("output-dir")
                        ? args.getOptionValues("output-dir").get(0) : "dump-output";
                int fileSizeMb            = parseIntOption(args, "file-size-mb", 256);
                int chunkSize             = parseIntOption(args, "chunk-size", 200000);
                int concurrency           = parseIntOption(args, "concurrency", 4);

                ctx.put("dumpOutputDir",       outputDir);
                ctx.put("dumpFileSizeMb",      fileSizeMb);
                ctx.put("dumpChunkSize",       chunkSize);
                ctx.put("dumpConcurrency",     concurrency);

                long threshold = fileSizeMb <= 0
                        ? Long.MAX_VALUE : (long) fileSizeMb * 1024 * 1024;
                steps.add(new DumpStep(config, extractor, driver.dumpExtractor(),
                        () -> new CsvDumpWriter(threshold), driver.consistencyProvider(), log));
            } else {
                steps.add(new SchemaMigrateStep(config, extractor, converter, writer, log, progress));
                steps.add(new VerifyStep(config, verifier, log));
            }

            StepResult result = new MigrationPipeline(steps).run(ctx);
            log.log("INFO", "Goodbye", "fatal", result.isFatal());

            if (result.isFatal()) {
                System.err.println("Error: " + result.message());
                System.exit(1);
            }
            Integer totalFailed = ctx.get("totalFailed", Integer.class);
            if (totalFailed != null && totalFailed > 0) System.exit(1);
        }
    }

    private DataSource createTargetDataSource() {
        var db = config.getTarget();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(db.tidbJdbcUrl());
        hikari.setUsername(db.getUsername());
        hikari.setPassword(db.getPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(30000);
        return new HikariDataSource(hikari);
    }

    private void runSnapshot(ApplicationArguments args,
                             List<String> databases, List<String> tables) throws Exception {
        int batchSize                = parseIntOption(args, "batch-size", SnapshotConfig.DEFAULT_BATCH_INSERT_SIZE);
        int fetchSize                = parseIntOption(args, "fetch-size", SnapshotConfig.DEFAULT_SNAPSHOT_FETCH_SIZE);
        int snapshotThreads          = parseIntOption(args, "snapshot-threads", SnapshotConfig.DEFAULT_SNAPSHOT_MAX_THREADS);
        boolean enableCdc       = args.containsOption("enable-cdc");
        String offsetStoragePath     = args.containsOption("offset-storage-path")
                ? args.getOptionValues("offset-storage-path").get(0) : null;
        String schemaHistoryPath     = args.containsOption("schema-history-path")
                ? args.getOptionValues("schema-history-path").get(0) : null;
        int maxQueueSize             = parseIntOption(args, "max-queue-size", SnapshotConfig.DEFAULT_MAX_QUEUE_SIZE);
        int pollIntervalMs           = parseIntOption(args, "poll-interval-ms", SnapshotConfig.DEFAULT_POLL_INTERVAL_MS);
        int offsetCommitIntervalMs   = parseIntOption(args, "offset-commit-interval-ms", SnapshotConfig.DEFAULT_OFFSET_COMMIT_INTERVAL_MS);

        try (StructuredLogger log = StructuredLogger.open("any2tidb.log")) {
            log.log("INFO", "Welcome to any2tidb v1.0.0 — Any DB → TiDB Migration Tool");
            log.log("INFO", "Connection info",
                    "source", config.getSource().getHost() + ":" + config.getSource().getPort(),
                    "target", config.getTarget().getHost() + ":" + config.getTarget().getPort(),
                    "mode", "snapshot");

            DataSource targetDs = createTargetDataSource();
            try {
                StepContext ctx = new StepContext();
                ctx.put("dryRun",            false);
                ctx.put("tables",            tables   != null ? tables   : List.of());
                ctx.put("databases",         databases != null ? databases : List.of());
                ctx.put("batchSize",         batchSize);
                ctx.put("fetchSize",         fetchSize);
                ctx.put("snapshotThreads",   snapshotThreads);
                ctx.put("enableCdc",        enableCdc);
                ctx.put("maxQueueSize",      maxQueueSize);
                ctx.put("pollIntervalMs",    pollIntervalMs);
                ctx.put("offsetCommitIntervalMs", offsetCommitIntervalMs);
                if (offsetStoragePath != null) ctx.put("offsetStoragePath", offsetStoragePath);
                if (schemaHistoryPath != null) ctx.put("schemaHistoryPath", schemaHistoryPath);

                List<MigrationStep> steps = new ArrayList<>();
                steps.add(new PreCheckStep(config));
                steps.add(new SnapshotStep(config, targetDs, log));

                StepResult result = new MigrationPipeline(steps).run(ctx);
                log.log("INFO", "Goodbye", "fatal", result.isFatal());

                if (result.isFatal()) {
                    System.err.println("Error: " + result.message());
                    System.exit(1);
                }
            } finally {
                if (targetDs instanceof HikariDataSource) {
                    ((HikariDataSource) targetDs).close();
                }
            }
        }
    }
}
