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
