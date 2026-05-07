# Task v2 Simplify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace state-machine-based tasks with simple execution records — one task = one mode run, no resume, no dependency.

**Architecture:** TaskMeta holds task name, mode, source/target info, and a flat result (status string + timestamps + error). TaskManager gains delete, loses transition/resume/fail. Runners always create new tasks (reject duplicates). File lock stays for cross-process exclusion.

**Tech Stack:** Java 17, Jackson JSON, NIO FileLock, Spring Boot CLI

---

### File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Delete | `src/main/java/com/tool/task/TaskState.java` | Remove state machine enum |
| Delete | `src/main/java/com/tool/task/PhaseInfo.java` | Remove per-phase tracking |
| Delete | `src/main/java/com/tool/task/PhaseState.java` | Remove phase state enum |
| Rewrite | `src/main/java/com/tool/task/TaskMeta.java` | Flat record: task, mode, status, source/target, timestamps, error |
| Rewrite | `src/main/java/com/tool/task/TaskManager.java` | create (reject dup), status, delete, lock/unlock |
| Keep | `src/main/java/com/tool/task/TaskLockedException.java` | Unchanged, still used for lock conflicts |
| Modify | `src/main/java/com/tool/SchemaRunner.java` | create-only path, write result on success/failure |
| Modify | `src/main/java/com/tool/DumpRunner.java` | create-only path, write result on success/failure |
| Modify | `src/main/java/com/tool/SnapshotRunner.java` | create-only path, write result on success/failure |
| Modify | `src/main/java/com/tool/SyncRunner.java` | create-only path, write result on exit |
| Modify | `src/main/java/com/tool/App.java` | task list/show rewritten for flat model, task delete added |
| Rewrite | `src/test/java/com/tool/task/TaskManagerTest.java` | Tests for new create/delete/lock behavior |

---

### Task 1: Rewrite TaskMeta — flat execution record

**Files:**
- Modify: `src/main/java/com/tool/task/TaskMeta.java`
- Delete: `src/main/java/com/tool/task/TaskState.java`
- Delete: `src/main/java/com/tool/task/PhaseInfo.java`
- Delete: `src/main/java/com/tool/task/PhaseState.java`

- [ ] **Step 1: Write the new TaskMeta**

Replace the entire content of `TaskMeta.java`:

```java
package com.tool.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskMeta {
    private String task;
    private String mode;        // "schema" | "dump" | "snapshot" | "sync"
    private SourceInfo source;
    private TargetInfo target;
    private String status;      // "running" | "success" | "failed"
    private String createdAt;
    private String startedAt;
    private String finishedAt;
    private Integer tables;
    private String error;

    public TaskMeta() {}  // for Jackson deserialization

    public static TaskMeta create(String taskName, String mode, String sourceType) {
        TaskMeta m = new TaskMeta();
        m.task = taskName;
        m.mode = mode;
        m.status = "running";
        m.createdAt = Instant.now().toString();
        m.startedAt = m.createdAt;
        SourceInfo src = new SourceInfo();
        src.setType(sourceType);
        m.source = src;
        return m;
    }

    public void markSuccess() {
        this.status = "success";
        this.finishedAt = Instant.now().toString();
    }

    public void markFailed(String err) {
        this.status = "failed";
        this.error = err;
        this.finishedAt = Instant.now().toString();
    }

    // Getters and setters
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public SourceInfo getSource() { return source; }
    public void setSource(SourceInfo source) { this.source = source; }
    public TargetInfo getTarget() { return target; }
    public void setTarget(TargetInfo target) { this.target = target; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Integer getTables() { return tables; }
    public void setTables(Integer tables) { this.tables = tables; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public static class SourceInfo {
        private String type;
        private String host;
        private int port;
        private String database;
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

- [ ] **Step 2: Delete obsolete files**

```bash
rm src/main/java/com/tool/task/TaskState.java
rm src/main/java/com/tool/task/PhaseInfo.java
rm src/main/java/com/tool/task/PhaseState.java
```

- [ ] **Step 3: Verify compilation fails (expected — callers not updated yet)**

Run: `mvn compile -q 2>&1 | head -20`
Expected: Compilation errors in TaskManager, SchemaRunner, DumpRunner, SnapshotRunner, SyncRunner, App — they still reference deleted types.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tool/task/TaskMeta.java
git add src/main/java/com/tool/task/TaskState.java src/main/java/com/tool/task/PhaseInfo.java src/main/java/com/tool/task/PhaseState.java
git commit -m "refactor: flatten TaskMeta to single-execution record, remove state machine types"
```

---

### Task 2: Rewrite TaskManager — create/reject, delete, no state machine

**Files:**
- Modify: `src/main/java/com/tool/task/TaskManager.java`

- [ ] **Step 1: Replace TaskManager**

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

    public TaskMeta create(String taskName, String mode, String sourceType) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task '" + taskName + "' already exists. " +
                    "Use a different name or delete the existing task first.");
        }
        Files.createDirectories(taskDir);

        acquireLock(taskDir, taskName);

        String pid = String.valueOf(ProcessHandle.current().pid());
        Files.writeString(taskDir.resolve(".pid"), pid);

        Files.createDirectories(taskDir.resolve("offsets"));
        Files.createDirectories(taskDir.resolve("history"));
        Files.createDirectories(taskDir.resolve("output"));

        TaskMeta meta = TaskMeta.create(taskName, mode, sourceType);
        writeMeta(taskDir, meta);

        return meta;
    }

    public void delete(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }

        Path lockFile = taskDir.resolve(".lock");
        if (Files.exists(lockFile)) {
            try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                 FileLock l = ch.tryLock()) {
                if (l == null) {
                    throw new TaskLockedException(taskName, getLockPid(taskDir));
                }
                l.release();
            } catch (TaskLockedException e) {
                throw e;
            } catch (Exception ignored) {
                // can't acquire lock → someone else has it
                throw new TaskLockedException(taskName, getLockPid(taskDir));
            }
        }

        try (var stream = Files.walk(taskDir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    private void acquireLock(Path taskDir, String taskName) throws Exception {
        lockChannel = FileChannel.open(
                taskDir.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lockChannel.close();
            throw new TaskLockedException(taskName, getLockPid(taskDir));
        }
        if (lock == null) {
            lockChannel.close();
            throw new TaskLockedException(taskName, getLockPid(taskDir));
        }
    }

    public TaskMeta readMeta(String taskName) throws Exception {
        Path metaFile = tasksRoot.resolve(taskName).resolve("meta.json");
        if (!Files.exists(metaFile)) {
            throw new IllegalArgumentException("meta.json not found for task: " + taskName);
        }
        return MAPPER.readValue(metaFile.toFile(), TaskMeta.class);
    }

    public TaskMeta status(String taskName) throws Exception {
        return readMeta(taskName);
    }

    public void writeMeta(String taskName, TaskMeta meta) throws Exception {
        writeMeta(tasksRoot.resolve(taskName), meta);
    }

    private void writeMeta(Path taskDir, TaskMeta meta) throws Exception {
        MAPPER.writeValue(taskDir.resolve("meta.json").toFile(), meta);
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

    public Path getTaskDir(String taskName) {
        return tasksRoot.resolve(taskName);
    }

    public void unlock() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
    }

    private String getLockPid(Path taskDir) {
        Path pidFile = taskDir.resolve(".pid");
        try {
            if (Files.exists(pidFile)) {
                return Files.readString(pidFile).trim();
            }
        } catch (IOException ignored) {}
        return "unknown";
    }
}
```

- [ ] **Step 2: Verify compilation errors reduced to Runner + App files only**

Run: `mvn compile -q 2>&1 | head -20`
Expected: Only SchemaRunner, DumpRunner, SnapshotRunner, SyncRunner, App errors remain.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/task/TaskManager.java
git commit -m "refactor: simplify TaskManager — create rejects dup, add delete, remove transition/resume/fail"
```

---

### Task 3: Rewrite TaskManagerTest for new behavior

**Files:**
- Rewrite: `src/test/java/com/tool/task/TaskManagerTest.java`

- [ ] **Step 1: Write the new test class**

```java
package com.tool.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;

class TaskManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createShouldBuildDirectoryAndLockAndMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        TaskMeta meta = tm.create("test-task", "dump", "sqlserver");

        assertEquals("test-task", meta.getTask());
        assertEquals("dump", meta.getMode());
        assertEquals("running", meta.getStatus());
        assertNotNull(meta.getCreatedAt());
        assertNotNull(meta.getStartedAt());
        assertTrue(tempDir.resolve("tasks/test-task/.lock").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/meta.json").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/offsets").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/history").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/output").toFile().exists());
        tm.unlock();
    }

    @Test
    void createRejectsDuplicateTaskName() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("dup-task", "dump", "sqlserver");
        tm.unlock();

        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tm2.create("dup-task", "snapshot", "sqlserver"));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    void lockPreventsDoubleStart() throws Exception {
        TaskManager tm1 = new TaskManager(tempDir.resolve("tasks"));
        tm1.create("lock-test", "dump", "sqlserver");

        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(TaskLockedException.class, () -> tm2.create("lock-test", "dump", "sqlserver"));

        tm1.unlock();
    }

    @Test
    void markSuccessUpdatesStatusAndTime() {
        TaskMeta meta = TaskMeta.create("t", "snapshot", "sqlserver");
        assertEquals("running", meta.getStatus());
        assertNull(meta.getFinishedAt());

        meta.markSuccess();
        assertEquals("success", meta.getStatus());
        assertNotNull(meta.getFinishedAt());
    }

    @Test
    void markFailedSetsStatusAndError() {
        TaskMeta meta = TaskMeta.create("t", "sync", "sqlserver");

        meta.markFailed("LSN not available");
        assertEquals("failed", meta.getStatus());
        assertEquals("LSN not available", meta.getError());
        assertNotNull(meta.getFinishedAt());
    }

    @Test
    void deleteRemovesTaskDirectory() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("del-test", "dump", "sqlserver");
        tm.unlock();

        assertTrue(Files.exists(tempDir.resolve("tasks/del-test")));

        tm.delete("del-test");
        assertFalse(Files.exists(tempDir.resolve("tasks/del-test")));
    }

    @Test
    void deleteThrowsForRunningTask() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("running", "dump", "sqlserver");

        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(TaskLockedException.class, () -> tm2.delete("running"));

        tm.unlock();
    }

    @Test
    void deleteThrowsForNonExistentTask() {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(IllegalArgumentException.class, () -> tm.delete("no-such-task"));
    }

    @Test
    void listTasksReturnsAllTaskNames() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("task-a", "dump", "sqlserver"); tm.unlock();
        tm.create("task-b", "snapshot", "sqlserver"); tm.unlock();

        var tasks = tm.list();
        assertTrue(tasks.contains("task-a"));
        assertTrue(tasks.contains("task-b"));
    }

    @Test
    void statusReadsMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("status-test", "schema", "sqlserver");
        tm.unlock();

        TaskMeta m = tm.status("status-test");
        assertEquals("status-test", m.getTask());
        assertEquals("schema", m.getMode());
        assertEquals("running", m.getStatus());
    }

    @Test
    void readMetaThrowsForMissingMetaFile() {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(IllegalArgumentException.class, () -> tm.readMeta("no-such-task"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=TaskManagerTest -DfailIfNoTests=false -q 2>&1`
Expected: COMPILATION ERROR (App.java and Runners still reference deleted types).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tool/task/TaskManagerTest.java
git commit -m "test: rewrite TaskManagerTest for v2 create/delete/markSuccess/markFailed"
```

---

### Task 4: Update SchemaRunner — create-only path

**Files:**
- Modify: `src/main/java/com/tool/SchemaRunner.java`

SchemaRunner's task block (lines 64-98) currently does resume-or-create + transition to `TaskState.DUMPING`. Replace with create-only, no state machine.

- [ ] **Step 1: Replace the task block**

Replace lines 64-98 (from `// Task support — mandatory` through `ctx.put("taskName", taskName);`):

```java
        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.create(taskName, "schema", "sqlserver");
        if (databases != null && !databases.isEmpty()) {
            meta.setDatabases(new ArrayList<>(databases));
        }
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);
        taskManager.writeMeta(taskName, meta);
        App.resolveTaskPaths(taskName, taskManager, ctx);
        ctx.put("taskManager", taskManager);
        ctx.put("taskName", taskName);
```

- [ ] **Step 2: Replace result handling block**

Replace lines 109-117 (the try/finally around transition/fail):

```java
        try {
            if (!result.isFatal()) {
                meta.markSuccess();
            } else {
                meta.markFailed(result.message());
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
```

- [ ] **Step 3: Remove unused imports**

Remove:
```java
import com.tool.task.TaskState;
```

- [ ] **Step 4: Compile to verify SchemaRunner passes**

Run: `mvn compile -q 2>&1`
Expected: Only DumpRunner, SnapshotRunner, SyncRunner errors remain.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/SchemaRunner.java
git commit -m "refactor: SchemaRunner uses create-only task path"
```

---

### Task 5: Update DumpRunner — create-only path

**Files:**
- Modify: `src/main/java/com/tool/DumpRunner.java`

DumpRunner's task block (lines 50-84) has the same resume-or-create + transition pattern. Replace with create-only.

- [ ] **Step 1: Replace the task block**

Replace lines 50-84 (from `// Task support — mandatory` through `ctx.put("taskName", taskName);`):

```java
        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.create(taskName, "dump", "sqlserver");
        if (databases != null && !databases.isEmpty()) {
            meta.setDatabases(new ArrayList<>(databases));
        }
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);
        taskManager.writeMeta(taskName, meta);
        App.resolveTaskPaths(taskName, taskManager, ctx);
        ctx.put("taskManager", taskManager);
        ctx.put("taskName", taskName);
```

- [ ] **Step 2: Replace result handling block**

Replace lines 111-119 (the try/finally around transition/fail):

```java
        try {
            if (!result.isFatal()) {
                meta.markSuccess();
            } else {
                meta.markFailed(result.message());
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
```

- [ ] **Step 3: Remove unused imports**

Remove:
```java
import com.tool.task.TaskState;
```

- [ ] **Step 4: Compile to verify DumpRunner passes**

Run: `mvn compile -q 2>&1`
Expected: Only SnapshotRunner and SyncRunner errors remain.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/DumpRunner.java
git commit -m "refactor: DumpRunner uses create-only task path"
```

---

### Task 6: Update SnapshotRunner — create-only path

**Files:**
- Modify: `src/main/java/com/tool/SnapshotRunner.java`

- [ ] **Step 1: Replace the task block (lines 68-114)**

```java
        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.create(taskName, "snapshot", "sqlserver");
        meta.setDatabases(databases != null ? databases : List.of());
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase(databases != null && !databases.isEmpty() ? databases.get(0) : "");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);
        taskManager.writeMeta(taskName, meta);

        App.resolveTaskPaths(taskName, taskManager, ctx);
```

Replace the result handling block (lines 121-131):

```java
        try {
            if (!result.isFatal()) {
                meta.markSuccess();
            } else {
                meta.markFailed(result.message());
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
```

Remove unused import:
```java
// Remove: import com.tool.task.TaskState;
```

- [ ] **Step 2: Compile to verify SnapshotRunner passes**

Run: `mvn compile -q 2>&1`
Expected: Only SyncRunner errors remain.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/SnapshotRunner.java
git commit -m "refactor: SnapshotRunner uses create-only task path"
```

---

### Task 7: Update SyncRunner — create-only path, no snapshot prerequisite

**Files:**
- Modify: `src/main/java/com/tool/SyncRunner.java`

- [ ] **Step 1: Replace the task block (lines 63-79)**

```java
        // Task support — mandatory
        if (!args.containsOption("task") || args.getOptionValues("task").isEmpty()) {
            throw new IllegalArgumentException("--task=NAME is required");
        }
        String taskName = args.getOptionValues("task").get(0);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("--task=NAME requires a non-empty name");
        }
        TaskManager taskManager = new TaskManager(Path.of("tasks"));
        TaskMeta meta = taskManager.create(taskName, "sync", "sqlserver");
        TaskMeta.SourceInfo src = meta.getSource();
        src.setHost(config.getSource().getHost());
        src.setPort(config.getSource().getPort());
        src.setDatabase("");
        TaskMeta.TargetInfo tgt = new TaskMeta.TargetInfo();
        tgt.setType("tidb");
        tgt.setHost(config.getTarget().getHost());
        tgt.setPort(config.getTarget().getPort());
        tgt.setDatabase("");
        meta.setTarget(tgt);
        taskManager.writeMeta(taskName, meta);

        App.resolveTaskPaths(taskName, taskManager, ctx);
```

Replace the result handling block (lines 97-107):

```java
        try {
            if (result.isFatal()) {
                meta.markFailed(result.message());
            } else {
                meta.markSuccess();
            }
            taskManager.writeMeta(taskName, meta);
        } finally {
            taskManager.unlock();
        }
```

Remove unused import:
```java
// Remove: import com.tool.task.TaskState;
```

- [ ] **Step 2: Verify full compilation passes**

Run: `mvn compile -q 2>&1`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/SyncRunner.java
git commit -m "refactor: SyncRunner uses create-only task path, removes snapshot prerequisite"
```

---

### Task 8: Update App.java — task list/show for new model, add task delete

**Files:**
- Modify: `src/main/java/com/tool/App.java`

- [ ] **Step 1: Update task help text (line 69)**

Replace lines 69-70:
```java
                System.out.println("Usage: any2tidb task list|show <name>");
                System.out.println();
                System.out.println("Commands:");
                System.out.println("  list         List all tasks with state and progress");
                System.out.println("  show <name>  Show detailed status of a task");
```
With:
```java
                System.out.println("Usage: any2tidb task list|show|delete <name>");
                System.out.println();
                System.out.println("Commands:");
                System.out.println("  list           List all tasks");
                System.out.println("  show <name>    Show detailed status of a task");
                System.out.println("  delete <name>  Delete a task (rejected if running)");
```

- [ ] **Step 2: Add delete handler (after line 82)**

After `System.out.println("Usage: any2tidb task list|show <name>");` in the else block on line 83, update to include delete.

Replace lines 76-88:
```java
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
```
With:
```java
            if (args.length >= 2) {
                String sub = args[1];
                if ("list".equals(sub)) {
                    taskList();
                } else if ("show".equals(sub) && args.length >= 3) {
                    taskShow(args[2]);
                } else if ("delete".equals(sub) && args.length >= 3) {
                    taskDelete(args[2]);
                } else {
                    System.out.println("Usage: any2tidb task list|show|delete <name>");
                }
            } else {
                System.out.println("Usage: any2tidb task list|show|delete <name>");
            }
```

- [ ] **Step 3: Update printUsage() task section (lines 166-168)**

Replace:
```java
        System.out.println("Task management:");
        System.out.println("  any2tidb task list         List all tasks with state and progress");
        System.out.println("  any2tidb task show <name>  Show detailed status of a task");
```
With:
```java
        System.out.println("Task management:");
        System.out.println("  any2tidb task list           List all tasks");
        System.out.println("  any2tidb task show <name>    Show detailed status of a task");
        System.out.println("  any2tidb task delete <name>  Delete a task (rejected if running)");
```

- [ ] **Step 4: Remove unused imports**

Remove lines 8-9:
```java
// Remove: import com.tool.task.PhaseInfo;
// Remove: import com.tool.task.PhaseState;
```

- [ ] **Step 5: Rewrite taskList()**

Replace lines 437-489:
```java
    private static void taskList() {
        try {
            Path tasksRoot = Path.of("tasks");
            if (!Files.exists(tasksRoot)) {
                System.out.println();
                System.out.println("No tasks found.");
                System.out.println();
                return;
            }
            var taskDirs = Files.list(tasksRoot)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            if (taskDirs.isEmpty()) {
                System.out.println();
                System.out.println("No tasks found.");
                System.out.println();
                return;
            }
            System.out.println();
            System.out.printf("%-24s %-10s %-10s %-15s %-15s%n",
                    "TASK", "MODE", "STATUS", "SOURCE", "TARGET");
            System.out.println("-".repeat(80));
            TaskManager tm = new TaskManager(tasksRoot);
            for (String name : taskDirs) {
                try {
                    TaskMeta m = tm.status(name);
                    TaskMeta.SourceInfo src = m.getSource();
                    TaskMeta.TargetInfo tgt = m.getTarget();
                    String sourceStr = src != null && src.getDatabase() != null && !src.getDatabase().isEmpty() ? src.getDatabase() : "?";
                    String targetStr = tgt != null && tgt.getDatabase() != null && !tgt.getDatabase().isEmpty() ? tgt.getDatabase() : "?";
                    System.out.printf("%-24s %-10s %-10s %-15s %-15s%n",
                            name,
                            m.getMode() != null ? m.getMode() : "?",
                            m.getStatus() != null ? m.getStatus() : "?",
                            sourceStr, targetStr);
                } catch (Exception e) {
                    System.out.printf("%-24s %-10s %-10s%n", name, "?", "error");
                }
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println();
            System.out.println("Error listing tasks: " + e.getMessage());
            System.out.println();
        }
    }
```

- [ ] **Step 6: Rewrite taskShow()**

Replace lines 491-554:
```java
    private static void taskShow(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            TaskMeta m = tm.status(name);

            System.out.println();
            System.out.println("TASK:    " + m.getTask());
            System.out.println("Mode:    " + (m.getMode() != null ? m.getMode() : "?"));
            System.out.println("Status:  " + (m.getStatus() != null ? m.getStatus() : "?"));
            System.out.println("Created: " + m.getCreatedAt());
            if (m.getStartedAt() != null) {
                System.out.println("Started: " + m.getStartedAt());
            }
            if (m.getFinishedAt() != null) {
                System.out.println("Finished:" + m.getFinishedAt());
            }
            if (m.getTables() != null) {
                System.out.println("Tables:  " + m.getTables());
            }
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

            if (m.getError() != null) {
                System.out.println();
                System.out.println("Error: " + m.getError());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println();
            System.out.println("Error: " + e.getMessage());
            System.out.println();
        }
    }
```

- [ ] **Step 7: Add taskDelete() method**

Add after taskShow():
```java
    private static void taskDelete(String name) {
        try {
            TaskManager tm = new TaskManager(Path.of("tasks"));
            tm.delete(name);
            System.out.println();
            System.out.println("Task '" + name + "' deleted.");
            System.out.println();
        } catch (TaskLockedException e) {
            System.out.println();
            System.out.println("Error: " + e.getMessage());
            System.out.println();
        } catch (Exception e) {
            System.out.println();
            System.out.println("Error: " + e.getMessage());
            System.out.println();
        }
    }
```

Add the import:
```java
// Add: import com.tool.task.TaskLockedException;
```
(Actually, `TaskLockedException` is already in the `task` package and doesn't need an import in App.java since it's currently not imported — but now we need to catch it. Add the import.)

The import block at the top should add:
```java
import com.tool.task.TaskLockedException;
```

- [ ] **Step 8: Verify compilation and tests pass**

Run: `mvn test -pl . -Dtest=TaskManagerTest -DfailIfNoTests=false 2>&1`
Expected: All tests pass, BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/tool/App.java
git commit -m "feat: update task list/show for flat model, add task delete subcommand"
```

---

### Task 9: End-to-end verification

- [ ] **Step 1: Run full test suite**

```bash
mvn test -q 2>&1
```

Expected: All tests pass.

- [ ] **Step 2: Verify task help output**

```bash
java -jar dist/any2tidb-1.0.0.jar task --help
```

Expected:
```
Usage: any2tidb task list|show|delete <name>

Commands:
  list           List all tasks
  show <name>    Show detailed status of a task
  delete <name>  Delete a task (rejected if running)
```

- [ ] **Step 3: Verify --task=NAME creates and writes meta.json**

```bash
# Create a quick run (will fail on network but should create task dir)
set +e
java -jar dist/any2tidb-1.0.0.jar sqlserver dump --databases=test --task=verify-test 2>&1
set -e
cat tasks/verify-test/meta.json 2>&1
```

Expected: meta.json with `"task":"verify-test"`, `"mode":"dump"`, `"status":"running"` or `"failed"` (depending on how far it gets).

- [ ] **Step 4: Verify duplicate rejection**

```bash
set +e
java -jar dist/any2tidb-1.0.0.jar sqlserver dump --databases=test --task=verify-test 2>&1
set -e
```

Expected: "Task 'verify-test' already exists."

- [ ] **Step 5: Verify task list shows the task**

```bash
java -jar dist/any2tidb-1.0.0.jar task list 2>&1
```

Expected: Shows verify-test with mode=dump and some status.

- [ ] **Step 6: Verify task delete**

```bash
java -jar dist/any2tidb-1.0.0.jar task delete verify-test 2>&1
ls tasks/verify-test 2>&1
```

Expected: "Task 'verify-test' deleted." then "No such file or directory".

- [ ] **Step 7: Clean up and commit if any fixes were needed**

```bash
git status
```
