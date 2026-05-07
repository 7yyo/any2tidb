package com.tool;

import ch.qos.logback.classic.Level;
import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.task.TaskLockedException;
import com.tool.task.TaskManager;
import com.tool.task.TaskMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;

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
    private final SchemaWriter writer;
    private final List<SourceDriver> drivers;

    public App(AppConfig config, SchemaWriter writer, List<SourceDriver> drivers) {
        this.config  = config;
        this.writer  = writer;
        this.drivers = drivers;
    }

    private SourceDriver selectDriver(String source) {
        for (SourceDriver d : drivers) {
            if (d.type().equals(source)) return d;
        }
        throw new IllegalArgumentException("No SourceDriver for '" + source + "'. Available: "
                + drivers.stream().map(SourceDriver::type).toList());
    }

    public static void main(String[] args) {
        // Prevent MySQL AbandonedConnectionCleanupThread from hitting NoClassDefFoundError during JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(
                com.mysql.cj.jdbc.AbandonedConnectionCleanupThread::uncheckedShutdown));

        // Handle task subcommands before general help (so "task --help" works)
        if (args.length >= 1 && "task".equals(args[0])) {
            if (args.length >= 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
                printTaskUsage();
                return;
            }
            if (args.length < 2) {
                printTaskUsage();
                return;
            }
            String a1 = args[1];
            // top-level: list, clean
            if ("list".equals(a1)) {
                taskList(args.length >= 3 && "--all".equals(args[2]));
                return;
            }
            if ("clean".equals(a1)) {
                taskClean();
                return;
            }
            // task <name> [action]
            String name = a1;
            if (args.length == 2) {
                taskShow(name);
            } else {
                switch (args[2]) {
                    case "stop"    -> taskStop(name);
                    case "pause"   -> taskPause(name);
                    case "resume"  -> taskResume(name);
                    case "restart" -> taskRestart(name);
                    case "delete"  -> taskDelete(name);
                    case "history" -> taskHistory(name);
                    default -> say("Usage: a2t task " + name + " stop|pause|resume|restart|delete|history"
                            + "\nRun 'a2t task --help' for details.");
                }
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
                    sayError("unknown source '" + source + "'. Valid: " + SOURCES);
                } else if (!modesFor(source).contains(mode)) {
                    sayError("unknown mode '" + mode + "' for " + source
                            + ". Valid: " + modesFor(source));
                } else {
                    printUsage(source, mode);
                }
            } else if (pos.size() == 1) {
                String arg = pos.get(0);
                if (SOURCES.contains(arg)) {
                    printUsage(arg);
                } else if (SOURCES.stream().flatMap(s -> modesFor(s).stream()).anyMatch(arg::equals)) {
                    sayError("source is required. Usage: a2t <source> " + arg);
                } else {
                    sayError("unknown argument '" + arg + "'\nRun 'a2t --help' for usage.");
                }
            } else {
                printUsage();
            }
            return;
        }
        if (Arrays.asList(args).contains("--version")) {
            String buildTime = "unknown";
            try {
                java.io.InputStream is = App.class.getClassLoader().getResourceAsStream("build.info");
                if (is != null) {
                    java.util.Properties p = new java.util.Properties();
                    p.load(is);
                    String val = p.getProperty("build.time");
                    if (val != null) buildTime = val;
                    is.close();
                }
            } catch (Exception ignored) {}
            say("any2tidb 1.0.0\nBuilt: " + buildTime);
            return;
        }
        SpringApplication app = new SpringApplication(App.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.run(args);
    }

    // ── Help / Usage ──────────────────────────────────────────────────────────

    private static final Set<String> SOURCES = Set.of("sqlserver", "mysql");

    private static List<String> modesFor(String source) {
        return switch (source) {
            case "mysql"     -> List.of("schema", "snapshot", "sync");
            case "sqlserver" -> List.of("schema", "dump", "snapshot", "sync");
            default          -> List.of();
        };
    }

    private static String modeDescription(String mode) {
        return switch (mode) {
            case "schema"   -> "schema        Migrate schema (DDL) only";
            case "dump"     -> "dump          Export data as CSV (Dumpling format)";
            case "snapshot" -> "snapshot      Stream full data directly to TiDB via Debezium CDC";
            case "sync"     -> "sync          Stream CDC changes to TiDB (requires prior snapshot)";
            default         -> mode;
        };
    }

    private static List<String> positionalArgs(String[] args) {
        List<String> out = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--") || (arg.startsWith("-") && !arg.startsWith("--"))) continue;
            out.add(arg);
        }
        return out;
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("a2t — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: a2t <source> <mode> [options]");
        System.out.println("       a2t task <command> [args]");
        System.out.println();
        System.out.println("Sources:");
        for (String s : SOURCES) System.out.println("  " + s);
        System.out.println();
        System.out.println("Each source supports different modes. Run 'a2t <source> --help'");
        System.out.println("to see available modes. Run 'a2t task --help' for task management.");
        System.out.println();
    }

    private static void printUsage(String source) {
        System.out.println();
        System.out.println("a2t " + source + " — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: a2t " + source + " <mode> [options]");
        System.out.println();
        System.out.println("Modes:");
        for (String m : modesFor(source)) {
            System.out.println("  " + modeDescription(m));
        }
        System.out.println();
        System.out.println("Run 'a2t " + source + " <mode> --help' for mode-specific options.");
        System.out.println();
    }

    private static void printUsage(String source, String mode) {
        boolean needTask = true;
        System.out.println();
        System.out.println("a2t " + source + " " + mode + " — Any DB → TiDB Migration Tool");
        System.out.println();
        System.out.println("Usage: a2t " + source + " " + mode
                + " [--task=NAME] [options]");
        System.out.println();
        System.out.println("Options:");
        if (needTask) {
            System.out.println("  --task=NAME               Task name (default: <source>-<mode>-<timestamp>)");
            System.out.println("  --daemon                  Run task in background, return immediately");
        }
        if ("schema".equals(mode) || "dump".equals(mode)) {
            System.out.println("  --databases=db1,db2,...   Filter databases, e.g. HRDB,Inventory (default: all)");
            System.out.println("  --tables=t1,t2,...        Migrate only specified tables (default: all)");
        }
        if ("schema".equals(mode)) {
            System.out.println("  --dry-run                 Print DDL without executing (default: false)");
            System.out.println("  --drop-if-exists          DROP existing tables before creating (default: false)");
            System.out.println("  --schema-db-threads=N     Process N databases concurrently (default: 1)");
        }
        if ("dump".equals(mode)) {
            System.out.println("  --output-dir=PATH         Output directory (default: dump-output)");
            System.out.println("  --file-size-mb=N          Max CSV file size in MB, 0=unlimited (default: 256)");
            System.out.println("  --chunk-size=N            Rows per PK-range chunk (default: 200000)");
            System.out.println("  --concurrency=N           Concurrent table exports (default: 4)");
            System.out.println("  --no-snapshot             Dump directly from live database, skip DB snapshot");
        }
        if ("snapshot".equals(mode)) {
            System.out.println("  --databases=db1,db2,...   Only process specified databases (default: all)");
            System.out.println("  --tables=t1,t2,...        Only process specified tables (default: all)");
            System.out.println("  --snapshot-threads=N      Tables snapshotted in parallel, and TiDB write threads (default: 1)");
            System.out.println("  --snapshot-db-threads=N   Process N databases concurrently (default: 1)");
            System.out.println("  --batch-size=N            Rows per INSERT batch to TiDB (default: 500)");
            System.out.println("  --fetch-size=N            Rows per JDBC fetch from source during snapshot (default: 1000)");
            System.out.println("  --max-queue-size=N        Debezium internal queue capacity (default: 16384)");
            System.out.println("  --poll-interval-ms=N      Debezium poll interval in ms (default: 500)");
            System.out.println("  --offset-flush-ms=N       Offset file flush interval in ms (default: 10000)");
        }
        if ("sync".equals(mode)) {
            System.out.println("  --from-task=NAME          (REQUIRED) Read offsets/history from a prior snapshot or dump task");
            System.out.println("  --poll-interval-ms=N      Debezium poll interval in ms (default: 500)");
        }
        System.out.println("  --log-level=LEVEL         DEBUG, INFO, WARN, ERROR (default: INFO)");
        System.out.println();
        System.out.println("Configuration: application.yml in working directory (source/target connection only)");
        System.out.println();
    }

    private static void printTaskUsage() {
        System.out.println();
        System.out.println("Usage: a2t task <command|task-name> [action]");
        System.out.println();
        System.out.println("Inspect:");
        System.out.println("  list [--all]            List all tasks (--all includes deleted)");
        System.out.println("  <task-name>             Show task detail and available actions");
        System.out.println();
        System.out.println("Control a task:");
        System.out.println("  a2t task <task-name> stop       Gracefully stop");
        System.out.println("  a2t task <task-name> pause      Pause");
        System.out.println("  a2t task <task-name> resume     Resume");
        System.out.println("  a2t task <task-name> restart    Restart a crashed task");
        System.out.println();
        System.out.println("Housekeeping:");
        System.out.println("  a2t task <task-name> delete     Delete (rejected if running)");
        System.out.println("  a2t task <task-name> history    Show operation history");
        System.out.println("  a2t task clean                  Wipe ALL tasks");
        System.out.println();
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

    static void applyLogLevel(ApplicationArguments args) {
        if (!args.containsOption("log-level")) return;
        String val = args.getOptionValues("log-level").get(0).toUpperCase();
        Level lbLevel;
        switch (val) {
            case "DEBUG" -> lbLevel = Level.DEBUG;
            case "INFO"  -> lbLevel = Level.INFO;
            case "WARN"  -> lbLevel = Level.WARN;
            case "ERROR" -> lbLevel = Level.ERROR;
            default -> {
                sayErr("invalid --log-level '" + val + "', valid: DEBUG, INFO, WARN, ERROR");
                return;
            }
        }
        // com.tool follows the chosen level
        ch.qos.logback.classic.Logger toolLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.tool");
        toolLogger.setLevel(lbLevel);
        // io.debezium: visible only in DEBUG, otherwise OFF
        ch.qos.logback.classic.Logger debezLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.debezium");
        debezLogger.setLevel(lbLevel == Level.DEBUG ? Level.DEBUG : Level.OFF);
    }

    // ── Flag whitelists ───────────────────────────────────────────────────────

    private static final Set<String> COMMON_FLAGS = Set.of(
            "dry-run", "databases", "tables", "drop-if-exists", "log-level", "task", "daemon",
            "schema-db-threads"
    );
    private static final Set<String> DUMP_FLAGS = Set.of(
            "output-dir", "file-size-mb", "chunk-size", "concurrency", "no-snapshot"
    );
    private static final Set<String> SNAPSHOT_FLAGS = Set.of(
            "batch-size", "fetch-size", "snapshot-threads", "snapshot-db-threads",
            "max-queue-size", "poll-interval-ms", "offset-flush-ms"
    );
    private static final Set<String> SYNC_FLAGS = Set.of(
            "poll-interval-ms", "from-task"
    );

    private Set<String> knownFlags(String source, String mode, SourceDriver driver) {
        Set<String> s = new java.util.LinkedHashSet<>(COMMON_FLAGS);
        if ("dump".equals(mode))     s.addAll(DUMP_FLAGS);
        if ("snapshot".equals(mode)) s.addAll(SNAPSHOT_FLAGS);
        if ("sync".equals(mode))     s.addAll(SYNC_FLAGS);
        s.addAll(driver.ownFlags());
        return s;
    }

    static String resolveTaskName(ApplicationArguments args, String source, String mode) {
        if (args.containsOption("task") && !args.getOptionValues("task").isEmpty()) {
            String name = args.getOptionValues("task").get(0);
            if (name != null && !name.isBlank()) return name;
        }
        return source + "-" + mode + "-"
                + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    // ── Daemon ─────────────────────────────────────────────────────────────────

    /**
     * Resolves y/N conflict with existing tasks in the foreground, then forks
     * a child JVM to do the actual work.  The child runs with {@code --no-interactive}
     * so it never prompts for a TTY again.
     */
    private static void launchDaemon(ApplicationArguments args, String taskName) throws Exception {

        // ── resolve name conflict BEFORE forking (we still have a TTY) ──
        TaskManager tm = new TaskManager(java.nio.file.Path.of("tasks"));
        try {
            TaskMeta existing = tm.readMeta(taskName);
            // DELETED tasks are treated as gone — silently overwrite
            if ("DELETED".equals(existing.getStatus())) {
                tm.delete(taskName);
            } else {
                System.out.println();
                System.out.println("Task:      " + existing.getTask());
                System.out.println("Mode:      " + (existing.getMode() != null ? existing.getMode() : "?"));
                System.out.println("Status:    " + (existing.getStatus() != null ? existing.getStatus() : "?"));
                if (existing.getFromTask() != null) {
                    System.out.println("Parent:    " + existing.getFromTask());
                }
                System.out.println("Created:   " + existing.getCreatedAt());
                if (existing.getFinishedAt() != null) {
                    System.out.println("Finished:  " + existing.getFinishedAt());
                }
                System.out.println("Source:    " + peerStr(existing.getSource()));
                System.out.println("Target:    " + peerStr(existing.getTarget()));
                if (existing.getError() != null) {
                    System.out.println("Error:     " + existing.getError());
                }
                System.out.println();
                System.out.print("Task '" + taskName + "' already exists. Delete and recreate? [y/N] ");
                System.out.flush();
                String answer = System.console() != null
                        ? System.console().readLine().trim().toLowerCase() : "n";
                if (!"y".equals(answer) && !"yes".equals(answer)) {
                    say("Aborted.");
                    return;
                }
                tm.delete(taskName);
            }
        } catch (IllegalArgumentException e) {
            // task doesn't exist — fine, proceed
        }

        // ── build child args (filter out --daemon) ──
        List<String> childArgs = new ArrayList<>();
        for (String a : args.getSourceArgs()) {
            if (!"--daemon".equals(a)) childArgs.add(a);
        }

        long pid = launchChildProcess(childArgs);
        say("Task '" + taskName + "' started in background (PID: " + pid + ")\nUse 'a2t task list' to check status.");
    }

    /**
     * Launches a child JVM with the given source args (must NOT contain --daemon).
     * Returns the PID of the child process.
     */
    private static long launchChildProcess(List<String> sourceArgs) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String jarPath = null;
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.endsWith(".jar") && new java.io.File(entry).exists()) {
                jarPath = entry;
                break;
            }
        }
        if (jarPath == null) {
            throw new RuntimeException("Cannot find any2tidb jar on classpath");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-jar");
        cmd.add(jarPath);
        cmd.addAll(sourceArgs);

        Process p = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(new java.io.File("/dev/null"))
                .start();
        return p.pid();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Print a message wrapped in leading/trailing blank lines. */
    private static void say(String msg) {
        System.out.println();
        System.out.println(msg);
        System.out.println();
    }

    /** Print an error message wrapped in leading/trailing blank lines. */
    private static void sayError(String msg) {
        say("Error: " + msg);
    }

    /** Print a message to stderr wrapped in leading/trailing blank lines. */
    private static void sayErr(String msg) {
        System.err.println();
        System.err.println(msg);
        System.err.println();
    }

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

    private DataSource createTargetDataSource(int dbThreads) {
        var db = config.getTarget();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(db.tidbJdbcUrl());
        hikari.setUsername(db.getUsername());
        hikari.setPassword(db.getPassword());
        hikari.setMaximumPoolSize(Math.max(10, dbThreads * 5 + 5));
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
            say("Usage: a2t <source> <mode> [options]");
            return;
        }
        String source = pos.get(0);
        String mode   = pos.get(1);

        if (!SOURCES.contains(source)) {
            sayError("unknown source '" + source + "'. Valid: " + SOURCES);
            return;
        }
        SourceDriver driver = selectDriver(source);

        if (!driver.supportedModes().contains(mode)) {
            sayError("unknown mode '" + mode + "' for " + source + ". Valid: " + driver.supportedModes());
            return;
        }

        Set<String> allowed = knownFlags(source, mode, driver);
        for (String name : args.getOptionNames()) {
            if (!allowed.contains(name)) {
                sayError("unknown option --" + name + " for " + source + " " + mode
                        + "\nRun 'a2t " + source + " " + mode + " --help' for usage.");
                return;
            }
        }

        applyLogLevel(args);

        // ── Daemon ──
        if (args.containsOption("daemon")) {
            String taskName = resolveTaskName(args, source, mode);
            // validate --from-task (sync)
            if ("sync".equals(mode)) {
                if (!args.containsOption("from-task") || args.getOptionValues("from-task").isEmpty()) {
                    sayErr("--from-task=NAME is required for sync mode");
                    return;
                }
                String fromTask = args.getOptionValues("from-task").get(0);
                if (fromTask.isBlank()) {
                    sayErr("--from-task=NAME requires a non-empty name");
                    return;
                }
                // verify parent task exists before forking
                TaskManager preTm = new TaskManager(java.nio.file.Path.of("tasks"));
                try {
                    TaskMeta parent = preTm.readMeta(fromTask);
                    if (!"snapshot".equals(parent.getMode()) && !"dump".equals(parent.getMode())) {
                        sayErr("--from-task '" + fromTask
                                + "' is a " + parent.getMode() + " task, must be snapshot or dump");
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    sayErr("--from-task '" + fromTask + "' not found");
                    return;
                }
            }
            launchDaemon(args, taskName);
            return;
        }

        // ── Dispatch ──

        if ("snapshot".equals(mode)) {
            List<String> databases = parseListOption(args, "databases");
            List<String> tables    = parseListOption(args, "tables");
            int dbThreads = parseIntOption(args, "snapshot-db-threads", 1);
            DataSource targetDs = createTargetDataSource(dbThreads);
            try {
                new SnapshotRunner(config, targetDs, driver).run(args, databases, tables, dbThreads);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sayErr("Error: " + e.getMessage());
            } finally {
                closeDataSource(targetDs);
            }
            return;
        }

        if ("sync".equals(mode)) {
            DataSource targetDs = createTargetDataSource(1);
            try {
                new SyncRunner(config, targetDs, driver).run(args);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sayErr("Error: " + e.getMessage());
            } finally {
                closeDataSource(targetDs);
            }
            return;
        }

        // ── schema ──

        if ("schema".equals(mode)) {
            boolean dryRun         = args.containsOption("dry-run");
            boolean dropIfExists   = args.containsOption("drop-if-exists");
            List<String> databases = parseListOption(args, "databases");
            List<String> tables    = parseListOption(args, "tables");
            int dbThreads          = parseIntOption(args, "schema-db-threads", 1);

            try {
                new SchemaRunner(config, driver.schemaExtractor(),
                        writer, driver.verifier(), driver)
                        .run(args, databases, tables, dryRun, dropIfExists, dbThreads);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sayErr("Error: " + e.getMessage());
            }
            return;
        }

        // ── dump ──

        if ("dump".equals(mode)) {
            List<String> databases = parseListOption(args, "databases");
            List<String> tables    = parseListOption(args, "tables");

            try {
                new DumpRunner(config, driver.schemaExtractor(), driver)
                        .run(args, databases, tables);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sayErr("Error: " + e.getMessage());
            }
            return;
        }
    }

    // ── Task subcommands ─────────────────────────────────────────────────────

    private static String availableActions(String status) {
        if (status == null) return "";
        return switch (status) {
            case "RUNNING" -> "stop | pause";
            case "PAUSED"  -> "resume | stop";
            case "CRASHED" -> "restart";
            default         -> "";
        };
    }

    private static void taskList(boolean showAll) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            List<TaskMeta> metas;
            if (showAll) {
                metas = tm.listAll();
            } else {
                List<String> names = tm.list();
                metas = new ArrayList<>();
                for (String name : names) {
                    try {
                        metas.add(tm.status(name));
                    } catch (Exception e) {
                        metas.add(null);
                    }
                }
            }

            // Zombie detection: for RUNNING tasks, check if PID is still alive
            for (TaskMeta m : metas) {
                if (m != null && "RUNNING".equals(m.getStatus()) && m.getPid() != null && !m.getPid().isEmpty()) {
                    if (!isPidAlive(m.getPid())) {
                        m.markCrashed("process " + m.getPid() + " terminated unexpectedly");
                        try { tm.writeMeta(m.getTask(), m); } catch (Exception ignored) {}
                    }
                }
            }

            if (metas.isEmpty()) {
                say("No tasks found.");
                return;
            }

            record TaskEntry(String name, TaskMeta meta) {}
            List<TaskEntry> entries = new ArrayList<>();
            for (TaskMeta m : metas) {
                entries.add(new TaskEntry(m != null ? m.getTask() : "?", m));
            }

            System.out.println();

            // Dynamically compute column widths
            int maxTaskW = "TASK".length();
            int maxSrcW  = "SOURCE".length();
            int maxTgtW  = "TARGET".length();
            for (TaskEntry e : entries) {
                if (e.name != null)            maxTaskW = Math.max(maxTaskW, e.name.length());
                if (e.meta != null) {
                    maxSrcW = Math.max(maxSrcW, peerStr(e.meta.getSource()).length());
                    maxTgtW = Math.max(maxTgtW, peerStr(e.meta.getTarget()).length());
                }
            }
            int nrW = Math.max(3, String.valueOf(entries.size()).length());

            String hdrFmt = "%-" + nrW + "s  %-" + maxTaskW + "s  %-8s  %-8s  %6s  %-" + maxSrcW + "s  %-" + maxTgtW + "s  %-14s%n";
            String rowFmt = "%-" + nrW + "s  %-" + maxTaskW + "s  %-8s  %s  %6s  %-" + maxSrcW + "s  %-" + maxTgtW + "s  %-14s%n";
            int[] w = {nrW, maxTaskW, 8, 8, 6, maxSrcW, maxTgtW, 14};

            System.out.printf(hdrFmt, "#", "TASK", "MODE", "STATUS", "PID", "SOURCE", "TARGET", "CREATED");
            for (int i = 0; i < w.length; i++) {
                System.out.print("-".repeat(w[i]));
                if (i < w.length - 1) System.out.print("  ");
            }
            System.out.println();

            int idx = 0;
            for (TaskEntry entry : entries) {
                idx++;
                TaskMeta m = entry.meta;
                if (m != null) {
                    String status = m.getStatus() != null ? m.getStatus() : "?";
                    String pidStr = m.getPid() != null ? m.getPid() : "-";
                    System.out.printf(rowFmt,
                            String.valueOf(idx),
                            entry.name,
                            m.getMode() != null ? m.getMode() : "?",
                            coloredStatus(status),
                            pidStr,
                            peerStr(m.getSource()),
                            peerStr(m.getTarget()),
                            shortTime(m.getCreatedAt()));
                } else {
                    System.out.printf(rowFmt, String.valueOf(idx), entry.name, "?", coloredStatus("error"), "-", "", "", "");
                }
            }
            System.out.println();
        } catch (Exception e) {
            say("Error listing tasks: " + e.getMessage());
        }
    }

    private static String coloredStatus(String status) {
        String padded = String.format("%-8s", status);
        return switch (status) {
            case "SUCCESS" -> "\033[1;32m" + padded + "\033[0m";  // bold green
            case "FAILED"  -> "\033[1;31m" + padded + "\033[0m";  // bold red
            case "RUNNING" -> "\033[1;36m" + padded + "\033[0m";  // bold cyan
            case "STOPPED" -> "\033[1;33m" + padded + "\033[0m";  // bold yellow
            case "PAUSED"  -> "\033[1;34m" + padded + "\033[0m";  // bold blue
            case "CRASHED" -> "\033[1;33m" + padded + "\033[0m";  // bold yellow
            case "DELETED" -> "\033[2;37m" + padded + "\033[0m";   // dim white
            default        -> padded;
        };
    }

    private static boolean isPidAlive(String pid) {
        try {
            long p = Long.parseLong(pid);
            return ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String shortTime(String createdAt) {
        if (createdAt == null || createdAt.length() < 16) return "?";
        return createdAt.substring(2, 16); // "2026-05-05 12:41:33 +0800" → "26-05-05 12:41"
    }

    private static String peerStr(TaskMeta.SourceInfo s) {
        if (s == null) return "?";
        String host = s.getHost();
        int port = s.getPort();
        if (host == null || host.isEmpty()) return "?";
        String type = s.getType() != null ? s.getType() : "?";
        return type + "://" + host + ":" + port;
    }

    private static String peerStr(TaskMeta.TargetInfo t) {
        if (t == null) return "?";
        String host = t.getHost();
        int port = t.getPort();
        if (host == null || host.isEmpty()) return "?";
        String type = t.getType() != null ? t.getType() : "?";
        return type + "://" + host + ":" + port;
    }

    private static void taskShow(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta m = tm.status(name);

            System.out.println();
            System.out.println("Task:      " + m.getTask());
            System.out.println("Mode:      " + (m.getMode() != null ? m.getMode() : "?"));
            System.out.println("Status:    " + (m.getStatus() != null ? m.getStatus() : "?")
                    + (m.getPid() != null ? " (pid=" + m.getPid() + ")" : ""));
            if (m.getFromTask() != null) {
                System.out.println("Parent:    " + m.getFromTask());
            }
            System.out.println("Created:   " + m.getCreatedAt());
            if (m.getStartedAt() != null && !m.getStartedAt().equals(m.getCreatedAt())) {
                System.out.println("Started:   " + m.getStartedAt());
            }
            if (m.getFinishedAt() != null) {
                System.out.println("Finished:  " + m.getFinishedAt());
            }
            if (m.getTables() != null) {
                System.out.println("Tables:    " + m.getTables());
            }
            System.out.println();

            TaskMeta.SourceInfo src = m.getSource();
            if (src != null) {
                System.out.println("Source:    " + peerStr(src));
            }
            TaskMeta.TargetInfo tgt = m.getTarget();
            if (tgt != null) {
                System.out.println("Target:    " + peerStr(tgt));
            }

            if (m.getError() != null) {
                System.out.println();
                System.out.println("Error:     " + m.getError());
            }

            String actions = availableActions(m.getStatus());
            if (!actions.isEmpty()) {
                System.out.println();
                System.out.println("Available actions: " + actions);
            }
            System.out.println();
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskHistory(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            List<TaskManager.HistoryEntry> entries = tm.history(name);
            if (entries.isEmpty()) {
                say("No history found for task '" + name + "'.");
                return;
            }

            say("Task: " + name);
            String hdrFmt = "%4s  %-20s  %-8s  %s%n";
            String rowFmt = "%4d  %-20s  %-8s  %s%n";
            System.out.printf(hdrFmt, "#", "TIMESTAMP", "ACTION", "DETAILS");
            System.out.println("----  --------------------  --------  ------------------------------");
            int idx = 0;
            boolean seenCreate = false;
            for (TaskManager.HistoryEntry e : entries) {
                if ("CREATE".equals(e.action())) {
                    if (seenCreate) {
                        System.out.println("      ── recreated ──");
                    }
                    seenCreate = true;
                }
                idx++;
                String ts = shortTime(e.createdAt());
                String details = e.details() != null ? e.details() : "";
                System.out.printf(rowFmt, idx, ts, e.action(), details);
            }
            System.out.println();
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskDelete(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            tm.delete(name);
            say("Task '" + name + "' deleted.");
        } catch (TaskLockedException e) {
            sayError(e.getMessage());
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskClean() {
        if (System.console() == null) {
            sayError("clean requires interactive terminal for confirmation.");
            return;
        }
        System.out.println();
        System.out.print("This will delete ALL tasks, history, and results. Are you sure? [y/N] ");
        System.out.flush();
        String answer = System.console().readLine().trim().toLowerCase();
        if (!"y".equals(answer) && !"yes".equals(answer)) {
            say("Aborted.");
            return;
        }
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            tm.cleanAll();
            say("All tasks and history cleared.");
        } catch (TaskLockedException e) {
            sayError(e.getMessage());
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskStop(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta meta = tm.status(name);
            if (!"RUNNING".equals(meta.getStatus()) && !"PAUSED".equals(meta.getStatus())) {
                say("Task '" + name + "' cannot be stopped (status: " + meta.getStatus() + ").");
                return;
            }
            if (!"sync".equals(meta.getMode())) {
                say("Task '" + name + "' is mode=" + meta.getMode() + ". Only sync tasks can be stopped.");
                return;
            }
            System.out.println();
            System.out.print("Stop task '" + name + "'? [y/N] ");
            String confirm = System.console().readLine();
            if (confirm == null || !confirm.trim().equalsIgnoreCase("y")) {
                say("Cancelled.");
                return;
            }
            tm.stop(name);
            // When paused, also remove .pause so the waiting thread can wake up and process the stop
            try { tm.resume(name); } catch (Exception ignored) {}
            System.out.println();
            System.out.print("Stop requested for task '" + name + "'. Waiting for graceful shutdown");
            System.out.flush();

            // Poll until the running process exits or timeout
            long deadline = System.currentTimeMillis() + 30_000;
            String status = meta.getStatus();
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                System.out.print(".");
                System.out.flush();
                TaskMeta current = tm.status(name);
                if (!"RUNNING".equals(current.getStatus()) && !"PAUSED".equals(current.getStatus())) {
                    status = current.getStatus();
                    break;
                }
                // Also check if the lock was released (process died without updating meta)
                Path lockFile = tm.getTaskDir(name).resolve(".internal/.lock");
                try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                        lockFile, java.nio.file.StandardOpenOption.WRITE);
                     java.nio.channels.FileLock ignoredLock = ch.tryLock()) {
                    // Lock acquired → process is gone
                    status = "STOPPED";
                    break;
                } catch (Exception ignored2) {
                    // Lock still held → process still running
                }
            }
            say("Task '" + name + "' " + status + ".");
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskPause(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta meta = tm.status(name);
            if (!"RUNNING".equals(meta.getStatus())) {
                say("Task '" + name + "' is not running (status: " + meta.getStatus() + ").");
                return;
            }
            if (!"sync".equals(meta.getMode())) {
                say("Task '" + name + "' is mode=" + meta.getMode() + ". Only sync tasks can be paused.");
                return;
            }
            System.out.println();
            System.out.print("Pause task '" + name + "'? [y/N] ");
            String confirm = System.console().readLine();
            if (confirm == null || !confirm.trim().equalsIgnoreCase("y")) {
                say("Cancelled.");
                return;
            }
            tm.pause(name);
            System.out.println();
            System.out.print("Pause requested for task '" + name + "'. Waiting");
            System.out.flush();

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                System.out.print(".");
                System.out.flush();
                TaskMeta current = tm.status(name);
                if ("PAUSED".equals(current.getStatus())) {
                    break;
                }
            }
            say("Task '" + name + "' paused.");
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskResume(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta meta = tm.status(name);
            if (!"PAUSED".equals(meta.getStatus())) {
                say("Task '" + name + "' is not paused (status: " + meta.getStatus() + ").");
                return;
            }
            if (!"sync".equals(meta.getMode())) {
                say("Task '" + name + "' is mode=" + meta.getMode() + ". Only sync tasks can be resumed.");
                return;
            }
            System.out.println();
            System.out.print("Resume task '" + name + "'? [y/N] ");
            String confirm = System.console().readLine();
            if (confirm == null || !confirm.trim().equalsIgnoreCase("y")) {
                say("Cancelled.");
                return;
            }
            tm.resume(name);
            System.out.println();
            System.out.print("Resume requested for task '" + name + "'. Waiting for engines to restart");
            System.out.flush();

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                System.out.print(".");
                System.out.flush();
                TaskMeta current = tm.status(name);
                if ("RUNNING".equals(current.getStatus())) {
                    break;
                }
            }
            say("Task '" + name + "' resumed.");
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    private static void taskRestart(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta meta = tm.status(name);

            if (!"CRASHED".equals(meta.getStatus())) {
                say("Task '" + name + "' is not crashed (status: " + meta.getStatus() + ").");
                return;
            }
            if (!"sync".equals(meta.getMode())) {
                say("Task '" + name + "' is mode=" + meta.getMode() + ". Only sync tasks can be restarted.");
                return;
            }

            List<String> storedArgs = meta.getArgs();
            if (storedArgs == null || storedArgs.isEmpty()) {
                say("Task '" + name + "' has no stored command-line args. Cannot restart.");
                return;
            }

            System.out.println();
            System.out.print("Restart task '" + name + "'? [y/N] ");
            System.out.flush();
            String confirm = System.console().readLine();
            if (confirm == null || !confirm.trim().equalsIgnoreCase("y")) {
                say("Cancelled.");
                return;
            }

            System.out.println("Restarting with args: " + String.join(" ", storedArgs));

            long pid = launchChildProcess(storedArgs);
            say("Task '" + name + "' restarted in background (PID: " + pid + ")\nUse 'a2t task list' to check status.");
        } catch (Exception e) {
            sayError(e.getMessage());
        }
    }

    static void resolveTaskPaths(String taskName,
            TaskManager tm, StepContext ctx) {
        Path taskDir = tm.getTaskDir(taskName);
        ctx.put("offsetStoragePath", taskDir.resolve(".internal/offsets").toString());
        ctx.put("schemaHistoryPath", taskDir.resolve(".internal/history").toString());
        ctx.put("dumpOutputDir", taskDir.resolve("output").toString());
    }
}
