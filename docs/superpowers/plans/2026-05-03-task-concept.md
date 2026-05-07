# Task Concept — Instance Isolation & Lifecycle Management

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `--task` flag that creates an isolated working directory per migration, tracks lifecycle via state machine, and supports concurrent independent migrations.

**Architecture:** New `TaskManager` class handles directory creation, `meta.json` read/write, FileLock, and state transitions. Each Runner class (SchemaDumpRunner, SnapshotRunner, SyncRunner) resolves its paths from the task directory instead of individual CLI flags. `any2tidb task list|show` subcommands provide visibility. Snapshot names add PID suffix for safety. Backward compatible: old `--xxx-path` flags still work when `--task` is not provided.

**Tech Stack:** Java 17, existing Spring Boot CLI (App.java), java.nio.channels.FileLock, Jackson for meta.json

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/tool/task/TaskManager.java` | **Create** | Directory creation, meta.json I/O, FileLock, state machine |
| `src/main/java/com/tool/task/TaskMeta.java` | **Create** | POJO: task name, source/target info, state, phases, errors |
| `src/main/java/com/tool/task/TaskState.java` | **Create** | Enum: CREATED, DUMPING, DUMPED, SNAPSHOTTING, SNAPSHOT, SYNCING, FAILED |
| `src/main/java/com/tool/task/PhaseInfo.java` | **Create** | POJO: phase name, state, timestamps, row counts |
| `src/main/java/com/tool/App.java` | **Modify** | `--task` flag parsing, `task list|show` subcommands |
| `src/main/java/com/tool/SchemaDumpRunner.java` | **Modify** | Resolve paths from TaskManager when --task present |
| `src/main/java/com/tool/SnapshotRunner.java` | **Modify** | Resolve paths from TaskManager when --task present |
| `src/main/java/com/tool/SyncRunner.java` | **Modify** | Resolve paths from TaskManager when --task present |
| `src/main/java/com/tool/source/SqlServerConsistencyProvider.java` | **Modify** | Snapshot name adds PID suffix |
| `src/main/java/com/tool/pipeline/steps/DumpStep.java` | **Modify** | Write meta.json phase transitions |
| `src/main/java/com/tool/snapshot/SnapshotStep.java` | **Modify** | meta.json updates, CWD file relocation |
| `src/test/java/com/tool/task/TaskManagerTest.java` | **Create** | Unit tests for lock, meta, state |
| `src/test/java/com/tool/task/TaskIntegrationTest.java` | **Create** | Integration test: dump → snapshot → sync with task |

---

### Task 1: TaskMeta, TaskState, PhaseInfo — Data Model

**Files:**
- Create: `src/main/java/com/tool/task/TaskState.java`
- Create: `src/main/java/com/tool/task/PhaseInfo.java`
- Create: `src/main/java/com/tool/task/TaskMeta.java`

- [ ] **Step 1: Create TaskState enum**

```java
package com.tool.task;

public enum TaskState {
    CREATED,
    DUMPING,
    DUMPED,
    SNAPSHOTTING,
    SNAPSHOT,
    SYNCING,
    FAILED;

    public boolean canTransitionTo(TaskState next) {
        return switch (this) {
            case CREATED      -> next == DUMPING || next == FAILED;
            case DUMPING      -> next == DUMPED  || next == FAILED;
            case DUMPED       -> next == SNAPSHOTTING || next == FAILED;
            case SNAPSHOTTING -> next == SNAPSHOT || next == FAILED;
            case SNAPSHOT     -> next == SYNCING || next == FAILED;
            case SYNCING      -> next == FAILED;
            case FAILED       -> next == DUMPING || next == SNAPSHOTTING || next == SYNCING; // resume
        };
    }
}
```

- [ ] **Step 2: Compile to verify enum**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 3: Create PhaseInfo POJO**

```java
package com.tool.task;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhaseInfo {
    private String state = "pending";  // pending | running | done | failed
    private String startedAt;
    private String finishedAt;
    private Integer tables;
    private Long rows;
    private String error;

    // Getters and setters
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Integer getTables() { return tables; }
    public void setTables(Integer tables) { this.tables = tables; }
    public Long getRows() { return rows; }
    public void setRows(Long rows) { this.rows = rows; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
```

- [ ] **Step 4: Create TaskMeta POJO**

```java
package com.tool.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskMeta {
    private String task;
    private String sourceType;
    private SourceInfo source;
    private TargetInfo target;
    private List<String> databases;
    private String state;
    private String createdAt;
    private Map<String, PhaseInfo> phases;
    private List<String> errors;

    public TaskMeta() {}

    public static TaskMeta create(String taskName, String sourceType) {
        TaskMeta m = new TaskMeta();
        m.task = taskName;
        m.sourceType = sourceType;
        m.state = TaskState.CREATED.name().toLowerCase();
        m.createdAt = Instant.now().toString();
        m.phases = new LinkedHashMap<>();
        m.phases.put("dump", new PhaseInfo());
        m.phases.put("snapshot", new PhaseInfo());
        m.phases.put("sync", new PhaseInfo());
        m.errors = new ArrayList<>();
        return m;
    }

    // Getters and setters
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public SourceInfo getSource() { return source; }
    public void setSource(SourceInfo source) { this.source = source; }
    public TargetInfo getTarget() { return target; }
    public void setTarget(TargetInfo target) { this.target = target; }
    public List<String> getDatabases() { return databases; }
    public void setDatabases(List<String> databases) { this.databases = databases; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public Map<String, PhaseInfo> getPhases() { return phases; }
    public void setPhases(Map<String, PhaseInfo> phases) { this.phases = phases; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }

    public static class SourceInfo {
        private String type;
        private String host;
        private int port;
        private String database;
        // getters and setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    public static class TargetInfo {
        private String type;
        private String host;
        private int port;
        private String database;
        // getters and setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }
}
```

- [ ] **Step 5: Compile to verify all three model classes**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tool/task/TaskState.java \
        src/main/java/com/tool/task/PhaseInfo.java \
        src/main/java/com/tool/task/TaskMeta.java
git commit -m "feat: add TaskState, PhaseInfo, TaskMeta data model for task concept"
```

---

### Task 2: TaskManager — Directory, Lock, and Meta I/O

**Files:**
- Create: `src/main/java/com/tool/task/TaskManager.java`
- Test: `src/test/java/com/tool/task/TaskManagerTest.java`

- [ ] **Step 1: Write failing tests for TaskManager**

```java
package com.tool.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

class TaskManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createShouldBuildDirectoryAndLockAndMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        TaskMeta meta = tm.create("test-task", "sqlserver");

        assertTrue(meta.getTask().equals("test-task"));
        assertTrue(meta.getState().equals("created"));
        assertTrue(tempDir.resolve("tasks/test-task/.lock").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/meta.json").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/offsets").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/history").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/output").toFile().exists());
        tm.unlock();
    }

    @Test
    void lockPreventsDoubleStart() throws Exception {
        TaskManager tm1 = new TaskManager(tempDir.resolve("tasks"));
        tm1.create("lock-test", "sqlserver");

        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(TaskLockedException.class, () -> tm2.create("lock-test", "sqlserver"));

        tm1.unlock();
    }

    @Test
    void resumeReadsExistingMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("resume-test", "sqlserver");
        tm.unlock();

        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        TaskMeta meta = tm2.resume("resume-test");
        assertEquals("created", meta.getState());
        tm2.unlock();
    }

    @Test
    void transitionStateUpdatesMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("state-test", "sqlserver");

        tm.transition("state-test", TaskState.DUMPING);
        TaskMeta meta = tm.readMeta("state-test");
        assertEquals("dumping", meta.getState());
        assertEquals("running", meta.getPhases().get("dump").getState());
        assertNotNull(meta.getPhases().get("dump").getStartedAt());

        tm.transition("state-test", TaskState.DUMPED);
        meta = tm.readMeta("state-test");
        assertEquals("dumped", meta.getState());
        assertEquals("done", meta.getPhases().get("dump").getState());
        assertNotNull(meta.getPhases().get("dump").getFinishedAt());

        tm.unlock();
    }

    @Test
    void listTasksReturnsAllTaskNames() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("task-a", "sqlserver"); tm.unlock();
        tm.create("task-b", "sqlserver"); tm.unlock();

        var tasks = tm.list();
        assertTrue(tasks.contains("task-a"));
        assertTrue(tasks.contains("task-b"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TaskManagerTest -DexcludedGroups=""`
Expected: FAIL — TaskManager class not found

- [ ] **Step 3: Create TaskLockedException**

```java
package com.tool.task;

public class TaskLockedException extends Exception {
    public TaskLockedException(String taskName, String lockedBy) {
        super("Task '" + taskName + "' is locked (PID: " + lockedBy + "). " +
              "If no other instance is running, remove tasks/" + taskName + "/.lock");
    }
}
```

- [ ] **Step 4: Implement TaskManager.create()**

```java
package com.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class TaskManager {
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    
    private final Path tasksRoot;
    private FileLock lock;
    private FileChannel lockChannel;

    public TaskManager(Path tasksRoot) {
        this.tasksRoot = tasksRoot;
    }

    public TaskMeta create(String taskName, String sourceType) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        Files.createDirectories(taskDir);
        
        // FileLock
        lockChannel = FileChannel.open(
                taskDir.resolve(".lock"), 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            String pid = Files.readString(taskDir.resolve(".pid")).trim();
            throw new TaskLockedException(taskName, pid);
        }
        // Write PID for diagnostics
        String pid = String.valueOf(ProcessHandle.current().pid());
        Files.writeString(taskDir.resolve(".pid"), pid);
        
        // Subdirectories
        Files.createDirectories(taskDir.resolve("offsets"));
        Files.createDirectories(taskDir.resolve("history"));
        Files.createDirectories(taskDir.resolve("output"));
        
        // meta.json
        TaskMeta meta = TaskMeta.create(taskName, sourceType);
        writeMeta(taskDir, meta);
        
        return meta;
    }

    public TaskMeta resume(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        
        // Same lock logic
        lockChannel = FileChannel.open(
                taskDir.resolve(".lock"), 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            String pid = Files.readString(taskDir.resolve(".pid")).trim();
            throw new TaskLockedException(taskName, pid);
        }
        Files.writeString(taskDir.resolve(".pid"), 
                String.valueOf(ProcessHandle.current().pid()));
        
        return readMeta(taskName);
    }

    public TaskMeta readMeta(String taskName) throws Exception {
        Path metaFile = tasksRoot.resolve(taskName).resolve("meta.json");
        if (!Files.exists(metaFile)) {
            throw new IllegalArgumentException("meta.json not found for task: " + taskName);
        }
        return MAPPER.readValue(metaFile.toFile(), TaskMeta.class);
    }

    public void transition(String taskName, TaskState newState) throws Exception {
        TaskMeta meta = readMeta(taskName);
        TaskState current = TaskState.valueOf(meta.getState().toUpperCase());
        
        if (!current.canTransitionTo(newState)) {
            throw new IllegalStateException(
                    "Cannot transition " + current + " → " + newState + " for task " + taskName);
        }
        
        meta.setState(newState.name().toLowerCase());
        
        String now = java.time.Instant.now().toString();
        String phaseKey = switch (newState) {
            case DUMPING, DUMPED                -> "dump";
            case SNAPSHOTTING, SNAPSHOT          -> "snapshot";
            case SYNCING                        -> "sync";
            default                             -> null;
        };
        
        if (phaseKey != null) {
            PhaseInfo phase = meta.getPhases().get(phaseKey);
            if (newState.name().endsWith("ING")) {
                phase.setState("running");
                phase.setStartedAt(now);
            } else {
                phase.setState("done");
                phase.setFinishedAt(now);
            }
        }
        
        writeMeta(tasksRoot.resolve(taskName), meta);
    }

    public void fail(String taskName, String error) throws Exception {
        TaskMeta meta = readMeta(taskName);
        meta.setState("failed");
        meta.getErrors().add(error);
        
        // Mark current running phase as failed
        for (PhaseInfo phase : meta.getPhases().values()) {
            if ("running".equals(phase.getState())) {
                phase.setState("failed");
                phase.setError(error);
            }
        }
        
        writeMeta(tasksRoot.resolve(taskName), meta);
    }

    public List<String> list() throws Exception {
        if (!Files.exists(tasksRoot)) return List.of();
        try (var stream = Files.list(tasksRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public TaskMeta status(String taskName) throws Exception {
        return readMeta(taskName);
    }

    public Path getTaskDir(String taskName) {
        return tasksRoot.resolve(taskName);
    }

    public void unlock() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
    }

    private void writeMeta(Path taskDir, TaskMeta meta) throws Exception {
        MAPPER.writeValue(taskDir.resolve("meta.json").toFile(), meta);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TaskManagerTest -DexcludedGroups=""`
Expected: PASS — 5/5 tests

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tool/task/TaskManager.java \
        src/main/java/com/tool/task/TaskLockedException.java \
        src/test/java/com/tool/task/TaskManagerTest.java
git commit -m "feat: add TaskManager with FileLock, meta I/O, and state machine"
```

---

### Task 3: CLI — `--task` Flag and `task list|show` Subcommands

**Files:**
- Modify: `src/main/java/com/tool/App.java`

- [ ] **Step 1: Add `--task` to help text and argument parsing**

In `App.java`, add to the shared options around line 132 (before `--help`):

```java
// In printUsage() method, before --help line (around line 131):
System.out.println("  --task=NAME               Task namespace for isolation (auto: <db>-<timestamp>)");

// In printSourceModeUsage(), add --task to all modes that need it:
// dump mode (around line 168):
System.out.println("  --task=NAME               Task namespace (auto: <db>-<timestamp>)");

// snapshot mode (around line 175):
System.out.println("  --task=NAME               Task namespace (auto: <db>-<timestamp>)");

// sync mode (around line 188):
System.out.println("  --task=NAME               Task namespace (auto: <db>-<timestamp>)");
```

- [ ] **Step 2: Add `task list|show` subcommand support**

In `App.java` main(), add after the `--version` check (after line 92):

```java
// Check for task subcommands
if (args.length >= 2 && "task".equals(args[0])) {
    String sub = args[1];
    if ("list".equals(sub)) {
        taskList(args);
    } else if ("show".equals(sub) && args.length >= 3) {
        taskShow(args[2]);
    } else {
        System.out.println("Usage: any2tidb task list|show <name>");
    }
    return;
}
```

- [ ] **Step 3: Implement taskList() and taskShow()**

```java
private static void taskList(String[] args) {
    try {
        TaskManager tm = new TaskManager(Path.of("tasks"));
        var names = tm.list();
        if (names.isEmpty()) {
            System.out.println("No tasks found.");
            return;
        }
        System.out.printf("%-20s %-12s %-15s %-15s %s%n", 
                "TASK", "STATE", "SOURCE", "TARGET", "PROGRESS");
        System.out.println("-".repeat(90));
        for (String name : names) {
            try {
                TaskMeta m = tm.status(name);
                PhaseInfo dump = m.getPhases().get("dump");
                PhaseInfo sync = m.getPhases().get("sync");
                String progress = "";
                if ("done".equals(dump.getState()) && dump.getRows() != null) {
                    progress = dump.getRows() + " rows";
                }
                if ("running".equals(sync.getState())) {
                    progress += progress.isEmpty() ? "CDC active" : ", CDC active";
                }
                String source = m.getSource() != null ? m.getSource().getDatabase() : "?";
                String target = m.getTarget() != null ? m.getTarget().getDatabase() : "?";
                System.out.printf("%-20s %-12s %-15s %-15s %s%n",
                        name, m.getState(), source, target, progress);
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
        if (m.getSource() != null) {
            var s = m.getSource();
            System.out.printf("Source:  %s %s:%d/%s%n", s.getType(), s.getHost(), s.getPort(), s.getDatabase());
        }
        if (m.getTarget() != null) {
            var t = m.getTarget();
            System.out.printf("Target:  %s %s:%d/%s%n", t.getType(), t.getHost(), t.getPort(), t.getDatabase());
        }
        System.out.printf("State:   %s%n", m.getState());
        System.out.printf("Started: %s%n", m.getCreatedAt());
        System.out.println();

        System.out.println("Phases:");
        for (var entry : m.getPhases().entrySet()) {
            PhaseInfo p = entry.getValue();
            String icon = switch (p.getState()) {
                case "done" -> "✓";
                case "running" -> "●";
                case "failed" -> "✗";
                default -> "○";
            };
            String line = String.format("  %s   %-12s", entry.getKey(), icon + " " + p.getState());
            if (p.getStartedAt() != null) {
                line += "  " + p.getStartedAt().substring(0, 19).replace("T", " ");
                if (p.getFinishedAt() != null) {
                    line += " — " + p.getFinishedAt().substring(0, 19).replace("T", " ");
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
                System.out.println("  " + err);
            }
        }
    } catch (Exception e) {
        System.out.println("Error: " + e.getMessage());
    }
}
```

- [ ] **Step 4: Add task path resolution helper for Runner classes**

```java
// In App.java, package-private helper:
static void resolveTaskPaths(ApplicationArguments args, String taskName, 
        TaskManager tm, StepContext ctx) throws Exception {
    Path taskDir = tm.getTaskDir(taskName);
    ctx.put("offsetStoragePath", taskDir.resolve("offsets").toString());
    ctx.put("schemaHistoryPath", taskDir.resolve("history").toString());
    ctx.put("dumpOutputDir", taskDir.resolve("output").toString());
}
```

- [ ] **Step 5: Compile and verify help text**

Run: `mvn compile -q && java -jar target/any2tidb-1.0.0.jar --help`
Expected: `--task=NAME` appears in help output

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tool/App.java
git commit -m "feat: add --task flag and task list|show subcommands"
```

---

### Task 4: Wire TaskManager into Runners (dump, snapshot, sync)

**Files:**
- Modify: `src/main/java/com/tool/SchemaDumpRunner.java`
- Modify: `src/main/java/com/tool/SnapshotRunner.java`
- Modify: `src/main/java/com/tool/SyncRunner.java`

- [ ] **Step 1: Add task support to SchemaDumpRunner.run()**

In `SchemaDumpRunner.java`, after line 63 (`ctx.put("databases", ...)`), add:

```java
// Task support
TaskManager taskManager = null;
if (args.containsOption("task")) {
    String taskName = args.getOptionValues("task").get(0);
    taskManager = new TaskManager(Path.of("tasks"));
    TaskMeta meta;
    if (Files.exists(taskManager.getTaskDir(taskName))) {
        meta = taskManager.resume(taskName);
    } else {
        meta = taskManager.create(taskName, "sqlserver");
        meta.setDatabases(databases);
        // Set source/target from config
        TaskMeta.SourceInfo src = new TaskMeta.SourceInfo();
        src.setType("sqlserver");
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
        meta.setSource(src);
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        meta.setTarget(tgt);
    }
    App.resolveTaskPaths(args, taskName, taskManager, ctx);
    taskManager.transition(taskName, TaskState.DUMPING);
    ctx.put("taskManager", taskManager);
    ctx.put("taskName", taskName);
}
```

Then in `App.handleResult(result, ctx)` or at the end of `run()`, add:

```java
// In SchemaDumpRunner.run(), after MigrationPipeline runs:
if (taskManager != null && result.isSuccess()) {
    taskManager.transition(taskName, TaskState.DUMPED);
    PhaseInfo dump = taskManager.readMeta(taskName).getPhases().get("dump");
    dump.setTables((Integer) ctx.get("totalTables", Integer.class));
    dump.setRows((Long) ctx.get("totalRows", Long.class));
    taskManager.unlock();
} else if (taskManager != null) {
    taskManager.fail(taskName, result.errorMessage());
    taskManager.unlock();
}
```

- [ ] **Step 2: Add task support to SnapshotRunner.run()**

Same pattern. After line 55:

```java
TaskManager taskManager = null;
String taskName = null;
if (args.containsOption("task")) {
    taskName = args.getOptionValues("task").get(0);
    taskManager = new TaskManager(Path.of("tasks"));
    TaskMeta meta = taskManager.resume(taskName);
    if (!meta.getState().equals("dumped") && !meta.getState().equals("snapshotting")) {
        throw new IllegalStateException("Task " + taskName + " is in state " + meta.getState() 
                + ", expected dumped. Run dump first.");
    }
    taskManager.transition(taskName, TaskState.SNAPSHOTTING);
    App.resolveTaskPaths(args, taskName, taskManager, ctx);
    ctx.put("taskManager", taskManager);
    ctx.put("taskName", taskName);
}
```

After pipeline run, transition to SNAPSHOT or FAILED.

- [ ] **Step 3: Add task support to SyncRunner.run()**

Same pattern, but the state check is for `snapshot` state, and transition to SYNCING.

- [ ] **Step 4: Compile**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/SchemaDumpRunner.java \
        src/main/java/com/tool/SnapshotRunner.java \
        src/main/java/com/tool/SyncRunner.java
git commit -m "feat: wire TaskManager into dump, snapshot, and sync runners"
```

---

### Task 5: SQL Server Snapshot Name + PID

**Files:**
- Modify: `src/main/java/com/tool/source/SqlServerConsistencyProvider.java`

- [ ] **Step 1: Add PID to snapshot name**

```java
// Line 31: add pid suffix constant
private static final String SNAP_SUFFIX = "_any2tidb_snap";
private static final long PID = ProcessHandle.current().pid();

// Line 150-152: change snapshotName()
public static String snapshotName(String dbName) {
    return dbName.toLowerCase() + SNAP_SUFFIX + "_" + PID;
}
```

- [ ] **Step 2: Update orphan cleanup to check PID liveness**

In `cleanOrphanSnapshots()` (around line 211), when finding snapshots with `SNAP_SUFFIX` in the name, parse the PID from the name and only drop if the PID is not alive:

```java
// In cleanOrphanSnapshots(), when iterating databases:
String name = rs.getString("name");
if (name.contains(SNAP_SUFFIX)) {
    // Extract PID from snapshot name
    int pidIdx = name.lastIndexOf("_");
    if (pidIdx > 0) {
        try {
            long snapPid = Long.parseLong(name.substring(pidIdx + 1));
            if (ProcessHandle.of(snapPid).isPresent()) {
                Log.info(log, "Skipping active snapshot", "name", name, "pid", snapPid);
                continue; // PID still alive, don't drop
            }
        } catch (NumberFormatException ignored) {
            // Old format without PID — drop it
        }
    }
    toDrop.add(name);
}
```

- [ ] **Step 3: Compile**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tool/source/SqlServerConsistencyProvider.java
git commit -m "feat: add PID suffix to SQL Server snapshot names for multi-instance safety"
```

---

### Task 6: CWD File Relocation

**Files:**
- Modify: `src/main/java/com/tool/snapshot/SnapshotStep.java`
- Modify: `src/main/java/com/tool/pipeline/steps/DumpStep.java`

- [ ] **Step 1: Move snapshot-meta.json to taskDir**

In `SnapshotStep.java`, find the line that writes `snapshot-meta.json` (line 294):

```java
// Before:
Files.writeString(Path.of("snapshot-meta.json"), json);

// After:
TaskManager tm = ctx.get("taskManager", TaskManager.class);
String taskName = ctx.get("taskName", String.class);
Path metaPath = (tm != null && taskName != null) 
        ? tm.getTaskDir(taskName).resolve("snapshot-meta.json")
        : Path.of("snapshot-meta.json");
Files.writeString(metaPath, json);
```

- [ ] **Step 2: Move dry-run .sql files to taskDir**

In `SchemaMigrateStep.java` (around line 72), do the same fallback-to-CWD logic:

```java
// Before:
Path filePath = Path.of(dbName + ".sql");

// After:
TaskManager tm = ctx.get("taskManager", TaskManager.class);
Path outputDir = (tm != null) ? tm.getTaskDir(ctx.get("taskName", String.class)) : Path.of(".");
Path filePath = outputDir.resolve(dbName + ".sql");
```

- [ ] **Step 3: Compile**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tool/snapshot/SnapshotStep.java \
        src/main/java/com/tool/pipeline/steps/DumpStep.java
git commit -m "refactor: relocate CWD temp files into task directory when --task is used"
```

---

### Task 7: Integration Test — Dump → Snapshot → Sync with Task

**Files:**
- Modify: `src/test/java/com/tool/DumpIntegrationTest.java`
- Create: `src/test/java/com/tool/task/TaskIntegrationTest.java`

- [ ] **Step 1: Add task-based test alongside existing DumpIntegrationTest**

```java
package com.tool.task;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

@Tag("integration")
class TaskIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void taskCreateAndLock() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        TaskMeta meta = tm.create("test-task", "sqlserver");

        assertEquals("test-task", meta.getTask());
        assertEquals("created", meta.getState());
        assertTrue(tempDir.resolve("tasks/test-task/meta.json").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/.lock").toFile().exists());

        // Second create should fail
        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(TaskLockedException.class, () -> tm2.create("test-task", "sqlserver"));

        tm.unlock();
    }

    @Test
    void stateTransitionsEnforced() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("state-test", "sqlserver");

        // Valid: created → dumping
        tm.transition("state-test", TaskState.DUMPING);

        // Invalid: cannot skip to syncing
        assertThrows(IllegalStateException.class,
                () -> tm.transition("state-test", TaskState.SYNCING));

        // Valid path
        tm.transition("state-test", TaskState.DUMPED);
        tm.transition("state-test", TaskState.SNAPSHOTTING);
        tm.transition("state-test", TaskState.SNAPSHOT);
        tm.transition("state-test", TaskState.SYNCING);

        tm.unlock();
    }

    @Test
    void taskListShowsAll() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("alpha", "sqlserver"); tm.unlock();
        tm.create("beta", "sqlserver"); tm.unlock();
        tm.create("gamma", "sqlserver"); tm.unlock();

        var list = tm.list();
        assertEquals(3, list.size());
        assertTrue(list.contains("alpha"));
        assertTrue(list.contains("beta"));
        assertTrue(list.contains("gamma"));
    }

    @Test
    void failRecordsError() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("fail-test", "sqlserver");
        tm.transition("fail-test", TaskState.DUMPING);
        tm.fail("fail-test", "LSN not available");

        TaskMeta meta = tm.readMeta("fail-test");
        assertEquals("failed", meta.getState());
        assertTrue(meta.getErrors().contains("LSN not available"));
        
        tm.unlock();
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `mvn test -Dtest=TaskIntegrationTest -DexcludedGroups=""`
Expected: PASS — 4/4 tests

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/task/TaskIntegrationTest.java
git commit -m "test: add TaskManager integration tests for lock, state, listing, and failure"
```

---

### Task 8: Smoke Test & Final Verification

- [ ] **Step 1: Run all non-integration tests**

Run: `mvn test`
Expected: all existing tests pass

- [ ] **Step 2: Run full integration test**

Run: `mvn test -Dtest=DumpIntegrationTest -DexcludedGroups=""`
Expected: PASS

- [ ] **Step 3: Build the final jar**

Run: `mvn package -DskipTests -q && cp target/any2tidb-1.0.0.jar dist/`

- [ ] **Step 4: Verify CLI help**

Run: `java -jar dist/any2tidb-1.0.0.jar --help`
Expected: `--task=NAME` visible, `task list|show` visible

- [ ] **Step 5: Verify task list empty**

Run: `java -jar dist/any2tidb-1.0.0.jar task list`
Expected: "No tasks found."

- [ ] **Step 6: Commit**

```bash
git add dist/any2tidb-1.0.0.jar
git commit -m "build: update dist jar with task concept support"
```
