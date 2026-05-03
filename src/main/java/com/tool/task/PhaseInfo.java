package com.tool.task;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhaseInfo {
    private PhaseState state = PhaseState.PENDING;
    private String startedAt;
    private String finishedAt;
    private Integer tables;
    private Long rows;
    private String error;

    public PhaseState getState() { return state; }
    public void setState(PhaseState state) { this.state = state; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Integer getTables() { return tables; }
    public void setTables(Integer tables) { this.tables = tables; }
    public Long getRows() { return rows; }
    public void setRows(Long rows) { this.rows = rows; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
