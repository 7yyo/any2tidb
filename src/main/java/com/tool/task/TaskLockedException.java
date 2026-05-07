package com.tool.task;

public class TaskLockedException extends Exception {
    public TaskLockedException(String taskName, String lockedBy) {
        super("Task '" + taskName + "' is locked (PID: " + lockedBy + "). " +
              "If no other instance is running, remove tasks/" + taskName + "/.internal/.lock");
    }
}
