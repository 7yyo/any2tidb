package com.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages task lifecycle with file-based locking for cross-process isolation.
 * Not thread-safe — each TaskManager instance is single-use per task.
 */
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

        acquireLock(taskDir, taskName);

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

        acquireLock(taskDir, taskName);

        Files.writeString(taskDir.resolve(".pid"),
                String.valueOf(ProcessHandle.current().pid()));

        return readMeta(taskName);
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

    public void transition(String taskName, TaskState newState) throws Exception {
        TaskMeta meta = readMeta(taskName);
        // transitionTo validates state machine and throws if invalid
        meta.transitionTo(newState);

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
                phase.setState(PhaseState.RUNNING);
                phase.setStartedAt(now);
            } else {
                phase.setState(PhaseState.DONE);
                phase.setFinishedAt(now);
            }
        }

        writeMeta(tasksRoot.resolve(taskName), meta);
    }

    public void fail(String taskName, String error) throws Exception {
        TaskMeta meta = readMeta(taskName);
        meta.setState(TaskState.FAILED);
        meta.getErrors().add(error);

        // Mark current running phase as failed
        for (PhaseInfo phase : meta.getPhases().values()) {
            if (phase.getState() == PhaseState.RUNNING) {
                phase.setState(PhaseState.FAILED);
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

    private String getLockPid(Path taskDir) {
        Path pidFile = taskDir.resolve(".pid");
        try {
            if (Files.exists(pidFile)) {
                return Files.readString(pidFile).trim();
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    public void writeMeta(String taskName, TaskMeta meta) throws Exception {
        writeMeta(tasksRoot.resolve(taskName), meta);
    }

    private void writeMeta(Path taskDir, TaskMeta meta) throws Exception {
        MAPPER.writeValue(taskDir.resolve("meta.json").toFile(), meta);
    }
}