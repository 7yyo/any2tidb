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

        assertEquals("test-task", meta.getTask());
        assertEquals(TaskState.CREATED, meta.getState());
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
        assertEquals(TaskState.CREATED, meta.getState());
        tm2.unlock();
    }

    @Test
    void transitionStateUpdatesMeta() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("state-test", "sqlserver");

        tm.transition("state-test", TaskState.DUMPING);
        TaskMeta meta = tm.readMeta("state-test");
        assertEquals(TaskState.DUMPING, meta.getState());
        assertEquals(PhaseState.RUNNING, meta.getPhases().get("dump").getState());
        assertNotNull(meta.getPhases().get("dump").getStartedAt());

        tm.transition("state-test", TaskState.DUMPED);
        meta = tm.readMeta("state-test");
        assertEquals(TaskState.DUMPED, meta.getState());
        assertEquals(PhaseState.DONE, meta.getPhases().get("dump").getState());
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

    @Test
    void failRecordsErrorAndMarksRunningPhaseFailed() throws Exception {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        tm.create("fail-test", "sqlserver");
        tm.transition("fail-test", TaskState.DUMPING);
        tm.fail("fail-test", "LSN not available");

        TaskMeta meta = tm.readMeta("fail-test");
        assertEquals(TaskState.FAILED, meta.getState());
        assertTrue(meta.getErrors().contains("LSN not available"));
        assertEquals(PhaseState.FAILED, meta.getPhases().get("dump").getState());
        assertEquals("LSN not available", meta.getPhases().get("dump").getError());

        tm.unlock();
    }

    @Test
    void resumeThrowsForNonExistentTask() {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(IllegalArgumentException.class, () -> tm.resume("no-such-task"));
    }

    @Test
    void readMetaThrowsForMissingMetaFile() {
        TaskManager tm = new TaskManager(tempDir.resolve("tasks"));
        assertThrows(IllegalArgumentException.class, () -> tm.readMeta("no-such-task"));
    }
}
