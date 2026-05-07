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
        assertEquals("RUNNING", meta.getStatus());
        assertNotNull(meta.getCreatedAt());
        assertNotNull(meta.getStartedAt());
        assertTrue(tempDir.resolve("tasks/test-task/.internal/.lock").toFile().exists());
        assertNotNull(tm.readMeta("test-task"));
        assertTrue(tempDir.resolve("tasks/test-task/.internal/offsets").toFile().exists());
        assertTrue(tempDir.resolve("tasks/test-task/.internal/history").toFile().exists());
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

        // Duplicate create is rejected (dir already exists) before lock check
        TaskManager tm2 = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> tm2.create("lock-test", "dump", "sqlserver"));

        tm1.unlock();
    }

    @Test
    void markSuccessUpdatesStatusAndTime() {
        TaskMeta meta = TaskMeta.create("t", "snapshot", "sqlserver");
        assertEquals("RUNNING", meta.getStatus());
        assertNull(meta.getFinishedAt());

        meta.markSuccess();
        assertEquals("SUCCESS", meta.getStatus());
        assertNotNull(meta.getFinishedAt());
    }

    @Test
    void markFailedSetsStatusAndError() {
        TaskMeta meta = TaskMeta.create("t", "sync", "sqlserver");

        meta.markFailed("LSN not available");
        assertEquals("FAILED", meta.getStatus());
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
        assertEquals("RUNNING", m.getStatus());
    }

    @Test
    void historyRecordsCreateStatusAndDelete() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        TaskMeta meta = tm.create("hist-test", "schema", "sqlserver");

        // CREATE should be recorded
        var h1 = tm.history("hist-test");
        assertEquals(1, h1.size());
        assertEquals("CREATE", h1.get(0).action());

        // Status change to SUCCESS via writeMeta
        meta.markSuccess();
        tm.writeMeta("hist-test", meta);

        var h2 = tm.history("hist-test");
        assertEquals(2, h2.size());
        assertEquals("CREATE", h2.get(0).action());
        assertEquals("SUCCESS", h2.get(1).action());

        tm.unlock();

        // DELETE should be recorded, and history survives task deletion
        tm.delete("hist-test");

        var h3 = tm.history("hist-test");
        assertEquals(3, h3.size());
        assertEquals("CREATE", h3.get(0).action());
        assertEquals("SUCCESS", h3.get(1).action());
        assertEquals("DELETE", h3.get(2).action());
    }

    @Test
    void readMetaThrowsForMissingTask() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("existing", "dump", "sqlserver");
        tm.unlock();
        assertThrows(IllegalArgumentException.class, () -> tm.readMeta("no-such-task"));
    }
}
