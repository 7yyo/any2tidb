package com.tool.task;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TaskMeta {
    private String task;
    private String mode;        // "schema" | "dump" | "snapshot" | "sync"
    private SourceInfo source;
    private TargetInfo target;
    private String status;      // "running" | "success" | "failed"
    private String createdAt;
    private String startedAt;
    private String finishedAt;
    private String fromTask;
    private Integer tables;
    private String error;
    private String pid;
    private List<String> args;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    public TaskMeta() {}  // for Jackson deserialization

    public static TaskMeta create(String taskName, String mode, String sourceType) {
        TaskMeta m = new TaskMeta();
        m.task = taskName;
        m.mode = mode;
        m.status = "RUNNING";
        m.createdAt = OffsetDateTime.now().format(FMT).toString();
        m.startedAt = m.createdAt;
        SourceInfo src = new SourceInfo();
        src.setType(sourceType);
        m.source = src;
        return m;
    }

    public void markSuccess() {
        this.status = "SUCCESS";
        this.finishedAt = OffsetDateTime.now().format(FMT).toString();
    }

    public void markStopped() {
        this.status = "STOPPED";
        this.finishedAt = OffsetDateTime.now().format(FMT).toString();
    }

    public void markPaused() {
        this.status = "PAUSED";
        this.finishedAt = OffsetDateTime.now().format(FMT).toString();
    }

    public void markResumed() {
        this.status = "RUNNING";
        this.finishedAt = null;
        this.startedAt = OffsetDateTime.now().format(FMT).toString();
    }

    public void markFailed(String err) {
        this.status = "FAILED";
        this.error = err;
        this.finishedAt = OffsetDateTime.now().format(FMT).toString();
    }

    public void markCrashed(String err) {
        this.status = "CRASHED";
        this.error = err;
        this.finishedAt = OffsetDateTime.now().format(FMT).toString();
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
    public String getFromTask() { return fromTask; }
    public void setFromTask(String fromTask) { this.fromTask = fromTask; }
    public Integer getTables() { return tables; }
    public void setTables(Integer tables) { this.tables = tables; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getPid() { return pid; }
    public void setPid(String pid) { this.pid = pid; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

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
