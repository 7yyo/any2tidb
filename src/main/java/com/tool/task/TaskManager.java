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
        Files.createDirectories(taskDir);

        acquireLock(taskDir, taskName);

        String pid = String.valueOf(ProcessHandle.current().pid());
        Files.writeString(taskDir.resolve(".pid"), pid);

        Files.createDirectories(taskDir.resolve("offsets"));
        Files.createDirectories(taskDir.resolve("history"));
        Files.createDirectories(taskDir.resolve("output"));

        TaskMeta meta = TaskMeta.create(taskName, mode, sourceType);
        dbManager.ensureInitialized();
        dbManager.insert(meta);

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
                throw new TaskLockedException(taskName, getLockPid(taskDir));
            }
        }

        dbManager.deleteByTask(taskName);
        try (var stream = Files.walk(taskDir)) {
            stream.sorted(Comparator.reverseOrder())
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
        dbManager.update(meta);
    }

    public List<String> list() throws Exception {
        dbManager.ensureInitialized();
        return dbManager.findAll().stream()
                .map(TaskMeta::getTask)
                .collect(Collectors.toList());
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
        Files.writeString(taskDir.resolve(".stop"),
                java.time.OffsetDateTime.now().toString());
    }

    /** Checks whether the .stop marker exists for this task. */
    public boolean isStopRequested(String taskName) {
        return Files.exists(tasksRoot.resolve(taskName).resolve(".stop"));
    }

    /** Writes a .pause marker to signal a running sync task to pause. */
    public void pause(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        Files.writeString(taskDir.resolve(".pause"),
                java.time.OffsetDateTime.now().toString());
    }

    /** Removes the .pause marker so the sync task resumes. */
    public void resume(String taskName) throws Exception {
        Path taskDir = tasksRoot.resolve(taskName);
        if (!Files.exists(taskDir)) {
            throw new IllegalArgumentException("Task not found: " + taskName);
        }
        Files.deleteIfExists(taskDir.resolve(".pause"));
    }

    /** Checks whether the .pause marker exists for this task. */
    public boolean isPauseRequested(String taskName) {
        return Files.exists(tasksRoot.resolve(taskName).resolve(".pause"));
    }

    public void unlock() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
        dbManager.close();
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
