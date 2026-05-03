package com.tool;

import com.tool.config.AppConfig;
import com.tool.loadgen.LoadGenerator;
import com.tool.logging.Log;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import com.tool.task.PhaseInfo;
import com.tool.task.PhaseState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;

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
import java.nio.file.Files;
import java.nio.file.Path;
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
        // Prevent MySQL AbandonedConnectionCleanupThread from hitting NoClassDefFoundError during JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(
                com.mysql.cj.jdbc.AbandonedConnectionCleanupThread::uncheckedShutdown));

        // Handle task subcommands before general help (so "task --help" works)
        if (args.length >= 1 && "task".equals(args[0])) {
            if (args.length >= 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
                System.out.println("Usage: any2tidb task list|show <name>");
                System.out.println();
                System.out.println("Commands:");
                System.out.println("  list         List all tasks with state and progress");
                System.out.println("  show <name>  Show detailed status of a task");
                return;
            }
            if (args.length >= 2) {
                String sub = args[1];
                if ("list".equals(sub)) {
                    taskList();
                } else if ("show".equals(sub) && args.length >= 3) {
                    taskShow(args[2]);
                } else {
                    System.out.println("Usage: any2tidb task list|show <name>");
                }
            } else {
                System.out.println("Usage: any2tidb task list|show <name>");
            }
            return;
        }

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

    // ── Help / Usage ──────────────────────────────────────────────────────────

    private static final Set<String> SOURCES = Set.of("sqlserver");
    private static final Set<String> MODES   = Set.of("schema", "dump", "snapshot", "sync", "loadgen");

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
        System.out.println("  sync          Stream CDC changes to TiDB (requires prior snapshot)");
        System.out.println("  loadgen       Generate continuous CRUD load on source database");
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
        System.out.println("  sync          Stream CDC changes to TiDB (requires prior snapshot)");
        System.out.println("  loadgen       Generate continuous CRUD load on source database");
        System.out.println();
        System.out.println();
        System.out.println("Run 'any2tidb " + source + " <mode> --help' for mode-specific options.");
    }

    private static void printUsage(String source, String mode) {
        System.out.println("any2tidb " + source + " " + mode + " — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: any2tidb " + source + " " + mode + " [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --log-level=LEVEL         DEBUG, INFO, WARN, ERROR (default: INFO)");
        if ("schema".equals(mode) || "dump".equals(mode)) {
            System.out.println("  --databases=db1,db2,...   Filter databases, e.g. HRDB,Inventory (default: all)");
            System.out.println("  --tables=t1,t2,...        Migrate only specified tables (default: all)");
            System.out.println("  --stop-on-error=true|false  Stop on first table failure (default: true)");
        }
        if ("schema".equals(mode)) {
            System.out.println("  --dry-run                 Print DDL without executing (default: false)");
            System.out.println("  --drop-if-exists          DROP existing tables before creating (default: false)");
        }
        if ("dump".equals(mode)) {
            System.out.println("  --output-dir=PATH         Output directory (default: dump-output)");
            System.out.println("  --file-size-mb=N          Max CSV file size in MB, 0=unlimited (default: 256)");
            System.out.println("  --chunk-size=N            Rows per PK-range chunk (default: 200000)");
            System.out.println("  --concurrency=N           Concurrent table exports (default: 4)");
            System.out.println("  --offset-storage-path=PATH  Debezium offset file dir (default: snapshot-offsets)");
            System.out.println("  --task=NAME               Task namespace for instance isolation");
        }
        if ("snapshot".equals(mode)) {
            System.out.println("  --databases=db1,db2,...   Only process specified databases (default: all)");
            System.out.println("  --tables=t1,t2,...        Only process specified tables (default: all)");
            System.out.println("  --batch-size=N            INSERT batch size (default: 10000)");
            System.out.println("  --fetch-size=N            Debezium snapshot fetch size (default: 10000)");
            System.out.println("  --snapshot-threads=N      Parallel chunk threads (default: 1)");
            System.out.println("  --offset-storage-path=PATH  Debezium offset file dir (default: snapshot-offsets)");
            System.out.println("  --schema-history-path=PATH  Debezium schema history dir (default: snapshot-schema-history)");
            System.out.println("  --max-queue-size=N        Debezium engine max queue size (default: 16384)");
            System.out.println("  --poll-interval-ms=N      Debezium poll interval in ms (default: 500)");
            System.out.println("  --offset-commit-interval-ms=N  Offset flush interval in ms (default: 10000)");
            System.out.println("  --snapshot-max-threads-multiplier=N  Thread multiplier (default: 1.0)");
            System.out.println("  --task=NAME               Task namespace for instance isolation");
        }
        if ("sync".equals(mode)) {
            System.out.println("  --poll-interval-ms=N      Debezium poll interval in ms (default: 500)");
            System.out.println("  --offset-storage-path=PATH  Debezium offset file dir (default: snapshot-offsets)");
            System.out.println("  --schema-history-path=PATH  Debezium schema history dir (default: snapshot-schema-history)");
            System.out.println("  --meta-file=PATH          Snapshot meta JSON file (default: snapshot-meta.json)");
            System.out.println("  --task=NAME               Task namespace for instance isolation");
        }
        if ("loadgen".equals(mode)) {
            System.out.println("  --database=NAME           Source database, e.g. HRDB (required)");
            System.out.println("  --rate=N                  Operations per second (default: 5)");
            System.out.println("  --duration=N              Run duration in seconds, 0=forever (default: 0)");
            System.out.println();
            System.out.println("  Targets hr.employees and hr.departments (hardcoded).");
        }
        System.out.println();
        System.out.println("Configuration: application.yml in working directory (source/target connection only)");
    }

    // ── CLI helpers ───────────────────────────────────────────────────────────

    static List<String> parseListOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) return null;
        List<String> values = args.getOptionValues(name);
        if (values.isEmpty()) return null;
        String val = values.get(0);
        if (val == null || val.isBlank()) return null;
        return Arrays.stream(val.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static int parseIntOption(ApplicationArguments args, String name, int defaultVal) {
        if (!args.containsOption(name)) return defaultVal;
        List<String> values = args.getOptionValues(name);
        if (values.isEmpty()) return defaultVal;
        try { return Integer.parseInt(values.get(0)); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    // ── Flag whitelists ───────────────────────────────────────────────────────

    private static final Set<String> COMMON_FLAGS = Set.of(
            "dry-run", "databases", "tables", "drop-if-exists", "stop-on-error", "log-level", "task"
    );
    private static final Set<String> DUMP_FLAGS = Set.of(
            "output-dir", "file-size-mb", "chunk-size", "concurrency", "offset-storage-path"
    );
    private static final Set<String> SNAPSHOT_FLAGS = Set.of(
            "batch-size", "fetch-size", "snapshot-threads",
            "offset-storage-path", "schema-history-path",
            "max-queue-size", "poll-interval-ms", "offset-commit-interval-ms",
            "snapshot-max-threads-multiplier"
    );
    private static final Set<String> SYNC_FLAGS = Set.of(
            "offset-storage-path", "schema-history-path",
            "poll-interval-ms", "meta-file"
    );
    private static final Set<String> LOADGEN_FLAGS = Set.of(
            "database", "rate", "duration"
    );

    private Set<String> knownFlags(String source, String mode) {
        Set<String> s = new java.util.LinkedHashSet<>(COMMON_FLAGS);
        if ("dump".equals(mode))     s.addAll(DUMP_FLAGS);
        if ("snapshot".equals(mode)) s.addAll(SNAPSHOT_FLAGS);
        if ("sync".equals(mode))     s.addAll(SYNC_FLAGS);
        if ("loadgen".equals(mode))  s.addAll(LOADGEN_FLAGS);
        s.addAll(driver.ownFlags());
        return s;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    static void handleResult(StepResult result, StepContext ctx) {
        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "Goodbye", "fatal", result.isFatal());
        if (result.isFatal()) {
            Log.error(log, result.message());
            System.exit(1);
        }
        Integer totalFailed = ctx.get("totalFailed", Integer.class);
        if (totalFailed != null && totalFailed > 0) System.exit(1);
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

    private void closeDataSource(DataSource ds) {
        if (ds instanceof HikariDataSource) {
            ((HikariDataSource) ds).close();
        }
    }

    // ── Main entry ────────────────────────────────────────────────────────────

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

        // ── Dispatch ──

        if ("snapshot".equals(mode)) {
            List<String> databases = parseListOption(args, "databases");
            List<String> tables    = parseListOption(args, "tables");
            DataSource targetDs = createTargetDataSource();
            try {
                new SnapshotRunner(config, targetDs, driver).run(args, databases, tables);
            } finally {
                closeDataSource(targetDs);
            }
            return;
        }

        if ("sync".equals(mode)) {
            DataSource targetDs = createTargetDataSource();
            try {
                new SyncRunner(config, targetDs, driver).run(args);
            } finally {
                closeDataSource(targetDs);
            }
            return;
        }

        if ("loadgen".equals(mode)) {
            runLoadGen(args);
            return;
        }

        // ── schema / dump ──

        boolean dryRun         = args.containsOption("dry-run");
        boolean dropIfExists   = args.containsOption("drop-if-exists");
        boolean stopOnError    = true;
        if (args.containsOption("stop-on-error")) {
            List<String> vals = args.getOptionValues("stop-on-error");
            if (!vals.isEmpty()) stopOnError = !"false".equalsIgnoreCase(vals.get(0));
        }
        boolean continueOnError = !stopOnError;
        List<String> databases = parseListOption(args, "databases");
        List<String> tables    = parseListOption(args, "tables");

        new SchemaDumpRunner(config, extractor, converter, writer, verifier, driver)
                .run(args, mode, databases, tables, dryRun, dropIfExists, continueOnError);
    }

    private void runLoadGen(ApplicationArguments args) throws Exception {
        String database = args.containsOption("database")
                ? args.getOptionValues("database").get(0) : null;
        int rate = parseIntOption(args, "rate", 5);
        int duration = parseIntOption(args, "duration", 0);

        if (database == null || database.isBlank()) {
            System.out.println("Error: --database is required for loadgen mode");
            System.exit(1);
        }

        Logger log = LoggerFactory.getLogger(App.class);
        Log.info(log, "loadgen mode — hr.employees + hr.departments CRUD load");

        LoadGenerator gen = new LoadGenerator(
                config.getSource(), database, rate, duration);
        gen.start();
    }

    // ── Task subcommands ─────────────────────────────────────────────────────

    private static void taskList() {
        try {
            Path tasksRoot = Path.of("tasks");
            if (!Files.exists(tasksRoot)) {
                System.out.println("No tasks found.");
                return;
            }
            var taskDirs = Files.list(tasksRoot)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            if (taskDirs.isEmpty()) {
                System.out.println("No tasks found.");
                return;
            }
            System.out.printf("%-20s %-12s %-15s %-15s %s%n",
                    "TASK", "STATE", "SOURCE", "TARGET", "PROGRESS");
            System.out.println("-".repeat(90));
            TaskManager tm = new TaskManager(tasksRoot);
            for (String name : taskDirs) {
                try {
                    TaskMeta m = tm.status(name);
                    PhaseInfo dump = m.getPhases().get("dump");
                    PhaseInfo snap = m.getPhases().get("snapshot");
                    String progress = "";
                    if (dump != null && dump.getState() == PhaseState.DONE && dump.getRows() != null) {
                        progress = String.format("%,d rows", dump.getRows());
                    }
                    if (snap != null && snap.getState() == PhaseState.DONE && snap.getRows() != null) {
                        progress += (progress.isEmpty() ? "" : ", ") + String.format("%,d snap rows", snap.getRows());
                    }
                    TaskMeta.SourceInfo src = m.getSource();
                    TaskMeta.TargetInfo tgt = m.getTarget();
                    String sourceStr = src != null ? src.getDatabase() : "?";
                    String targetStr = tgt != null ? tgt.getDatabase() : "?";
                    System.out.printf("%-20s %-12s %-15s %-15s %s%n",
                            name, m.getState() != null ? m.getState().toValue() : "?",
                            sourceStr, targetStr, progress);
                } catch (Exception e) {
                    System.out.printf("%-20s %-12s %s%n", name, "error", e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Error listing tasks: " + e.getMessage());
        }
    }

    private static void taskShow(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta m = tm.status(name);

            System.out.println("TASK: " + m.getTask());
            System.out.println();
            TaskMeta.SourceInfo src = m.getSource();
            if (src != null) {
                System.out.printf("Source:  %s %s:%d/%s%n",
                        src.getType() != null ? src.getType() : "?",
                        src.getHost() != null ? src.getHost() : "?",
                        src.getPort(),
                        src.getDatabase() != null ? src.getDatabase() : "?");
            }
            TaskMeta.TargetInfo tgt = m.getTarget();
            if (tgt != null) {
                System.out.printf("Target:  %s %s:%d/%s%n",
                        tgt.getType() != null ? tgt.getType() : "?",
                        tgt.getHost() != null ? tgt.getHost() : "?",
                        tgt.getPort(),
                        tgt.getDatabase() != null ? tgt.getDatabase() : "?");
            }
            System.out.printf("State:   %s%n", m.getState() != null ? m.getState().toValue() : "?");
            System.out.printf("Started: %s%n", m.getCreatedAt());
            System.out.println();

            System.out.println("Phases:");
            for (var entry : m.getPhases().entrySet()) {
                PhaseInfo p = entry.getValue();
                String stateName = p.getState() != null ? p.getState().name().toLowerCase() : "pending";
                String icon = switch (stateName) {
                    case "done" -> "\u2713";
                    case "running" -> "\u25cf";
                    case "failed" -> "\u2717";
                    default -> "\u25cb";
                };
                String line = String.format("  %-10s %s %-7s", entry.getKey(), icon, stateName);
                if (p.getStartedAt() != null) {
                    line += "  " + p.getStartedAt().substring(0, Math.min(19, p.getStartedAt().length())).replace("T", " ");
                    if (p.getFinishedAt() != null) {
                        line += " \u2014 " + p.getFinishedAt().substring(0, Math.min(19, p.getFinishedAt().length())).replace("T", " ");
                    }
                }
                if (p.getTables() != null) line += "  " + p.getTables() + " tables";
                if (p.getRows() != null) line += "  " + String.format("%,d rows", p.getRows());
                System.out.println(line);
            }

            if (m.getErrors() != null && !m.getErrors().isEmpty()) {
                System.out.println();
                System.out.println("Errors:");
                for (String err : m.getErrors()) {
                    System.out.println("  - " + err);
                }
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    static void resolveTaskPaths(String taskName,
            TaskManager tm, StepContext ctx) {
        Path taskDir = tm.getTaskDir(taskName);
        ctx.put("offsetStoragePath", taskDir.resolve("offsets").toString());
        ctx.put("schemaHistoryPath", taskDir.resolve("history").toString());
        ctx.put("dumpOutputDir", taskDir.resolve("output").toString());
    }
}
