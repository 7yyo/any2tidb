package com.tool.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskMeta {
    private String task;
    private SourceInfo source;
    private TargetInfo target;
    private List<String> databases;
    private TaskState state;
    private String createdAt;
    private Map<String, PhaseInfo> phases;
    private List<String> errors;

    public TaskMeta() {}  // for Jackson deserialization

    public static TaskMeta create(String taskName, String sourceType) {
        TaskMeta m = new TaskMeta();
        m.task = taskName;
        m.state = TaskState.CREATED;
        m.createdAt = Instant.now().toString();
        m.phases = new LinkedHashMap<>();
        m.phases.put("dump", new PhaseInfo());
        m.phases.put("snapshot", new PhaseInfo());
        m.phases.put("sync", new PhaseInfo());
        m.errors = new ArrayList<>();
        SourceInfo src = new SourceInfo();
        src.setType(sourceType);
        m.source = src;
        return m;
    }

    public void transitionTo(TaskState next) {
        if (this.state == null) {
            this.state = next;
            return;
        }
        if (!this.state.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Cannot transition " + this.state + " → " + next + " for task " + task);
        }
        this.state = next;
    }

    // Getters and setters
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public SourceInfo getSource() { return source; }
    public void setSource(SourceInfo source) { this.source = source; }
    public TargetInfo getTarget() { return target; }
    public void setTarget(TargetInfo target) { this.target = target; }
    public List<String> getDatabases() { return databases; }
    public void setDatabases(List<String> databases) { this.databases = databases; }
    public TaskState getState() { return state; }
    public void setState(TaskState state) { transitionTo(state); }
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
