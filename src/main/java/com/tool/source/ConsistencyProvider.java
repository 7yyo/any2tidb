package com.tool.source;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for obtaining a point-in-time consistent view of source data.
 * Each source database implements its own mechanism.
 * SQL Server: Database Snapshots (Enterprise/Developer edition).
 */
public interface ConsistencyProvider {

    /**
     * Check that the source instance meets prerequisites for the consistency
     * mechanism (e.g. Enterprise/Developer edition for SQL Server snapshots).
     * @throws Exception with a clear message if prerequisites are not met (fatal)
     */
    void checkPrerequisites(Connection masterConn) throws Exception;

    /**
     * Holds the result of a snapshot creation for a single database.
     *
     * @param snapName snapshot database name (used internally for JDBC connections and cleanup)
     * @param lsn      max LSN at snapshot creation time, formatted as {@code 0x...} hex string;
     *                 CDC is auto-enabled if necessary, so this is always non-null on success
     */
    record SnapshotInfo(String snapName, String lsn) {}

    /**
     * Create consistent point-in-time snapshots for the given databases.
     * @return map of sourceDbName → {@link SnapshotInfo} (snapshot name + LSN)
     */
    Map<String, SnapshotInfo> createSnapshots(Connection masterConn, List<String> dbNames,
                                              Path outputDir) throws Exception;

    /**
     * Drop all snapshots previously created by {@link #createSnapshots}.
     * Best-effort: exceptions on individual drops do not prevent further drops.
     */
    void dropSnapshots(Connection masterConn, List<String> snapshotIds) throws Exception;

    /**
     * Build a JDBC connection URL targeting the snapshot of the given source database.
     */
    String jdbcUrlForSnapshot(String sourceDbName, String snapshotName);
}
