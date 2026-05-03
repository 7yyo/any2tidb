package com.tool.source;

import java.sql.Connection;
import java.util.List;

public interface CdcProvider {

    record CdcCheckResult(
            boolean hasError,
            String errorMessage,
            boolean agentRunning,
            boolean cdcEnabled,
            List<String> tablesWithoutCdc
    ) {}

    boolean isAgentRunning(Connection conn) throws Exception;

    boolean isCdcEnabled(Connection conn, String dbName) throws Exception;

    List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                     List<String[]> tables) throws Exception;

    void enableCdc(Connection conn, String dbName) throws Exception;

    void enableCdcForTable(Connection conn, String dbName, String schema, String table) throws Exception;

    CdcCheckResult check(Connection conn, String dbName,
                          List<String[]> tables);
}
