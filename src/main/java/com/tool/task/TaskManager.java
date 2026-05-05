package com.tool.task;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class TaskManager {

    private final Path tasksRoot;
    private final DbManager dbManager;
    private FileLock lock;
    private FileChannel lockChannel;

    public TaskManager(Path tasksRoot) {
        this.tasksRoot = tasksRoot;
        this.dbManager = new DbManager(tasksRoot);
    }

    public TaskMeta create(String taskName, String mode, String sourceType) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task '" + taskName + "' already exists. " +
                    "Use a different name or delete the existing task first.");
        }
        return doCreate(taskName, mode, sourceType);
    }

    /**
     * Like {@link #create} but prompts to delete and recreate when a task with
     * the same name already exists. Falls back to {@link #create} when there is
     * no console (non-interactive mode).
     */
    public TaskMeta createInteractive(String taskName, String mode, String sourceType) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (Files.exists(taskDir)) {
            if (System.console() == null) {
                throw new IllegalArgumentException("Task '" + taskName + "' already exists. " +
                        "Use a different name or delete the existing task first.");
            }
            TaskMeta existing = readMeta(taskName);
            printTaskInfo(existing);
            System.out.println();
            System.out.print("Task '" + taskName + "' already exists. Delete and recreate? [y/N] ");
            System.out.flush();
            String answer = System.console().readLine().trim().toLowerCase();
            if ("y".equals(answer) || "yes".equals(answer)) {
                delete(taskName);
            } else {
                System.out.println("Aborted.");
                throw new IllegalArgumentException("Task '" + taskName + "' already exists. " +
                        "Use a different name or delete the existing task first.");
            }
        }
        return doCreate(taskName, mode, sourceType);
    }

    private TaskMeta doCreate(String taskName, String mode, String sourceType) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        Path internalDir = taskDir.resolve(".internal");
        Files.createDirectories(internalDir);

        acquireLock(taskDir, taskName);

        String pid = String.valueOf(ProcessHandle.current().pid());
        Files.writeString(internalDir.resolve(".pid"), pid);

        Files.writeString(internalDir.resolve("_DO_NOT_EDIT.txt"),
                "INTERNAL FILES — DO NOT EDIT OR DELETE\n" +
                "======================================\n" +
                "This directory contains files managed by any2tidb.\n" +
                "Modifying or deleting them will corrupt your migration tasks.\n");

        Files.createDirectories(internalDir.resolve("offsets"));
        Files.createDirectories(internalDir.resolve("history"));
        Files.createDirectories(taskDir.resolve("output"));

        TaskMeta meta = TaskMeta.create(taskName, mode, sourceType);
        meta.setPid(pid);
        dbManager.ensureInitialized();
        dbManager.insert(meta);
        dbManager.addHistory(taskName, "CREATE", null);

        return meta;
    }

    public void delete(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }

        Path lockFile = taskDir.resolve(".internal/.lock");
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
                throw new TaskLockedException(taskName, getLockPid(taskDir));
            }
        }

        dbManager.addHistory(taskName, "DELETE", null);
        dbManager.deleteResults(taskName);
        dbManager.markDeleted(taskName);
        try (var stream = Files.walk(taskDir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    private void acquireLock(Path taskDir, String taskName) throws Exception {
        lockChannel = FileChannel.open(
                taskDir.resolve(".internal/.lock"),
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
        TaskMeta meta = dbManager.findByTask(taskName);
        if (meta == null) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        return meta;
    }

    public TaskMeta status(String taskName) throws Exception {
        return readMeta(taskName);
    }

    public void writeMeta(String taskName, TaskMeta meta) throws Exception {
        TaskMeta old = dbManager.findByTask(taskName);
        dbManager.update(meta);
        String newStatus = meta.getStatus();
        if (newStatus != null && (old == null || !newStatus.equals(old.getStatus()))) {
            dbManager.addHistory(taskName, newStatus, meta.getError());
        }
    }

    public List<String> list() throws Exception {
        dbManager.ensureInitialized();
        return dbManager.findAll().stream()
                .map(TaskMeta::getTask)
                .collect(Collectors.toList());
    }

    public List<TaskMeta> listAll() throws Exception {
        dbManager.ensureInitialized();
        List<TaskMeta> all = new ArrayList<>(dbManager.findAll());
        all.addAll(dbManager.findDeleted());
        return all;
    }

    public List<TaskMeta> listDeleted() throws Exception {
        dbManager.ensureInitialized();
        return dbManager.findDeleted();
    }

    /**
     * Wipe everything — all tasks, history, results, and database.
     * Rejects if any task is currently locked (running).
     */
    public void cleanAll() throws Exception {
        dbManager.ensureInitialized();
        List<String> names = list();
        for (String name : names) {
            Path lockFile = tasksRoot.resolve(name).resolve(".internal/.lock");
            if (Files.exists(lockFile)) {
                try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                     FileLock l = ch.tryLock()) {
                    if (l == null) throw new TaskLockedException(name, getLockPid(tasksRoot.resolve(name)));
                    l.release();
                } catch (TaskLockedException e) {
                    throw e;
                } catch (Exception ignored) {
                    throw new TaskLockedException(name, getLockPid(tasksRoot.resolve(name)));
                }
            }
        }
        // also check deleted tasks that still have dirs on disk
        File[] dirs = tasksRoot.toFile().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                Path lockFile = dir.toPath().resolve(".internal/.lock");
                if (Files.exists(lockFile)) {
                    try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                         FileLock l = ch.tryLock()) {
                        if (l == null) throw new TaskLockedException(dir.getName(),
                                getLockPid(dir.toPath()));
                        l.release();
                    } catch (TaskLockedException e) {
                        throw e;
                    } catch (Exception ignored) {
                        throw new TaskLockedException(dir.getName(),
                                getLockPid(dir.toPath()));
                    }
                }
            }
        }

        dbManager.close();
        dbManager.deleteDatabase();

        // delete all task directories
        if (Files.exists(tasksRoot)) {
            try (var stream = Files.walk(tasksRoot)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ── Snapshot / Dump results ───────────────────────────────────────────

    public record SnapshotResult(String dbName, int tables, long rows, String error) {}
    public record DumpResult(String dbName, int tables, long rows, String startLsn) {}

    public void writeSnapshotResults(String taskName, List<SnapshotResult> results) throws Exception {
        List<DbManager.SnapshotRow> rows = results.stream()
                .map(r -> new DbManager.SnapshotRow(r.dbName, r.tables, r.rows, r.error))
                .toList();
        dbManager.insertSnapshotResults(taskName, rows);
    }

    public List<SnapshotResult> readSnapshotResults(String taskName) throws Exception {
        return dbManager.findSnapshotResults(taskName).stream()
                .map(r -> new SnapshotResult(r.dbName(), r.tables(), r.rows(), r.error()))
                .toList();
    }

    public void writeDumpResults(String taskName, List<DumpResult> results) throws Exception {
        List<DbManager.DumpRow> rows = results.stream()
                .map(r -> new DbManager.DumpRow(r.dbName, r.tables, r.rows, r.startLsn))
                .toList();
        dbManager.insertDumpResults(taskName, rows);
    }

    // ── History ─────────────────────────────────────────────────────────

    public record HistoryEntry(long id, String task, String action, String details, String createdAt) {}

    public List<HistoryEntry> history(String taskName) throws Exception {
        return dbManager.findHistory(taskName).stream()
                .map(r -> new HistoryEntry(r.id(), r.task(), r.action(), r.details(), r.createdAt()))
                .toList();
    }

    public Path getTaskDir(String taskName) {
        return tasksRoot.resolve(taskName);
    }

    /** Writes a .stop marker to signal a running sync task to graceful shutdown. */
    public void stop(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        Files.writeString(taskDir.resolve(".internal/.stop"),
                java.time.OffsetDateTime.now().toString());
        dbManager.addHistory(taskName, "STOP", null);
    }

    /** Checks whether the .stop marker exists for this task. */
    public boolean isStopRequested(String taskName) {
        return Files.exists(tasksRoot.resolve(taskName).resolve(".internal/.stop"));
    }

    /** Writes a .pause marker to signal a running sync task to pause. */
    public void pause(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        Files.writeString(taskDir.resolve(".internal/.pause"),
                java.time.OffsetDateTime.now().toString());
        dbManager.addHistory(taskName, "PAUSE", null);
    }

    /** Removes the .pause marker so the sync task resumes. */
    public void resume(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        Files.deleteIfExists(taskDir.resolve(".internal/.pause"));
        dbManager.addHistory(taskName, "RESUME", null);
    }

    /** Checks whether the .pause marker exists for this task. */
    public boolean isPauseRequested(String taskName) {
        return Files.exists(tasksRoot.resolve(taskName).resolve(".internal/.pause"));
    }

    public void unlock() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
        dbManager.close();
    }

    private String getLockPid(Path taskDir) {
        Path pidFile = taskDir.resolve(".internal/.pid");
        try {
            if (Files.exists(pidFile)) {
                return Files.readString(pidFile).trim();
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    private static void printTaskInfo(TaskMeta m) {
        System.out.println();
        System.out.println("Task:      " + m.getTask());
        System.out.println("Mode:      " + (m.getMode() != null ? m.getMode() : "?"));
        System.out.println("Status:    " + (m.getStatus() != null ? m.getStatus() : "?"));
        if (m.getFromTask() != null) {
            System.out.println("Parent:    " + m.getFromTask());
        }
        System.out.println("Created:   " + m.getCreatedAt());
        if (m.getFinishedAt() != null) {
            System.out.println("Finished:  " + m.getFinishedAt());
        }
        System.out.println("Source:    " + peerStr(m.getSource()));
        System.out.println("Target:    " + peerStr(m.getTarget()));
        if (m.getError() != null) {
            System.out.println("Error:     " + m.getError());
        }
    }

    private static String peerStr(TaskMeta.SourceInfo s) {
        if (s == null) return "?";
        String host = s.getHost();
        int port = s.getPort();
        if (host == null || host.isEmpty()) return "?";
        return host + ":" + port;
    }

    private static String peerStr(TaskMeta.TargetInfo t) {
        if (t == null) return "?";
        String host = t.getHost();
        int port = t.getPort();
        if (host == null || host.isEmpty()) return "?";
        return host + ":" + port;
    }
}
