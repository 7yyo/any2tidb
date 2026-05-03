package com.tool.source;

import static com.tool.source.sqlserver.SqlServerCdcUtils.captureLsn;
import static com.tool.common.SqlUtils.escapeBracket;

import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * SQL Server Database Snapshot manager implementing {@link ConsistencyProvider}.
 *
 * <p>Database Snapshots provide a read-only, point-in-time consistent copy of an
 * entire database. Multiple connections can read from the same snapshot in parallel,
 * enabling both inter-table and intra-table (PK-range) parallelism with true consistency.
 *
 * <p>Requires Enterprise or Developer edition. Uses sparse files (copy-on-write at
 * the data-page level) placed under {@code <outputDir>/snapshots/}.
 */
public class SqlServerConsistencyProvider implements ConsistencyProvider {

    private static final Logger log = LoggerFactory.getLogger(SqlServerConsistencyProvider.class);
    private static final String SNAP_SUFFIX = "_any2tidb_snap";

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public SqlServerConsistencyProvider(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    // ── ConsistencyProvider implementation ───────────────────────────────────

    @Override
    public void checkPrerequisites(Connection masterConn) throws Exception {
        String sql = "SELECT SERVERPROPERTY('EngineEdition')";
        try (Statement st = masterConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                int edition = rs.getInt(1);
                if (edition != 3 && edition != 11) {
                    String label = switch (edition) {
                        case 1 -> "Personal";
                        case 2 -> "Standard";
                        case 4 -> "Express";
                        case 5 -> "Azure SQL DB";
                        case 6 -> "Azure Synapse";
                        case 8 -> "Managed Instance";
                        case 9 -> "Azure SQL Edge";
                        default -> "Unknown (" + edition + ")";
                    };
                    throw new IllegalStateException(
                            "Database Snapshots require Enterprise or Developer edition. " +
                            "Detected: " + label + ". " +
                            "Consistent dump with parallelism is not available on this edition.");
                }
                Log.info(log, "Edition check passed", "edition", edition == 3 ? "Enterprise" : "Developer");
            } else {
                throw new IllegalStateException("Cannot determine SQL Server edition");
            }
        }
    }

    @Override
    public Map<String, SnapshotInfo> createSnapshots(Connection masterConn, List<String> dbNames,
                                                     Path outputDir) throws Exception {
        // Clean up orphan snapshots from previous crashed runs
        cleanOrphanSnapshots(masterConn);

        Path snapDir = outputDir.resolve("snapshots");
        Files.createDirectories(snapDir);

        Map<String, SnapshotInfo> result = new LinkedHashMap<>();
        List<String> created = new ArrayList<>();

        try {
            for (String dbName : dbNames) {
                String snapName = snapshotName(dbName);
                createSnapshot(masterConn, dbName, snapName, snapDir);
                created.add(snapName);

                String lsn = null;
                try {
                    lsn = ensureCdcAndCaptureLsn(dbName);
                    Log.info(log, "LSN captured", "database", dbName, "lsn", lsn);
                } catch (Exception e) {
                    Log.warn(log, "CDC/LSN capture failed, dump can proceed but sync will not be able to resume from this point",
                            "database", dbName, "error", e.getMessage());
                }
                result.put(dbName, new SnapshotInfo(snapName, lsn));
                Log.info(log, "Database snapshot created", "source", dbName, "snapshot", snapName,
                        "lsn", lsn != null ? lsn : "N/A");
            }
        } catch (Exception e) {
            // Rollback: drop already-created snapshots on snapshot creation failure
            for (String snap : created) {
                try { dropSnapshot(masterConn, snap); } catch (Exception ignored) {}
            }
            throw e;
        }

        return result;
    }

    /**
     * Ensure CDC is enabled on the source database, then capture the current max LSN.
     * The LSN serves as the CDC start point for incremental sync after the full dump.
     * Auto-enables CDC at the database level if not already enabled.
     *
     * @throws Exception if CDC cannot be enabled (fatal)
     */
    private String ensureCdcAndCaptureLsn(String dbName) throws Exception {
        return captureLsn(host, port, username, password, dbName);
    }

    @Override
    public void dropSnapshots(Connection masterConn, List<String> snapshotIds) throws Exception {
        for (String snapName : snapshotIds) {
            try {
                dropSnapshot(masterConn, snapName);
                Log.info(log, "Database snapshot dropped", "snapshot", snapName);
            } catch (Exception e) {
                Log.warn(log, "Failed to drop snapshot", "snapshot", snapName, "error", e.getMessage());
            }
        }
    }

    @Override
    public String jdbcUrlForSnapshot(String sourceDbName, String snapshotName) {
        return String.format(
                "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5",
                host, port, snapshotName);
    }

    // ── Snapshot operations ──────────────────────────────────────────────────

    public static String snapshotName(String dbName) {
        return dbName.toLowerCase() + SNAP_SUFFIX;
    }

    /**
     * Create a database snapshot for a single source database.
     */
    private void createSnapshot(Connection masterConn, String dbName, String snapName,
                                 Path snapDir) throws Exception {
        // Discover data files for this database
        List<FileEntry> files = new ArrayList<>();
        String sql = "SELECT name, type_desc FROM sys.master_files " +
                     "WHERE database_id = DB_ID(?) AND type_desc = 'ROWS'";
        try (PreparedStatement ps = masterConn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(new FileEntry(rs.getString("name"), rs.getString("type_desc")));
                }
            }
        }

        if (files.isEmpty()) {
            throw new IllegalStateException("No data files found for database '" + dbName + "'");
        }

        // Build CREATE DATABASE ... AS SNAPSHOT OF statement
        StringBuilder sb = new StringBuilder("CREATE DATABASE [")
                .append(escapeBracket(snapName)).append("] ON ");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(", ");
            FileEntry fe = files.get(i);
            String sparsePath = snapDir.resolve(snapName + "_" + fe.name + ".ss")
                    .toAbsolutePath().toString();
            sb.append("(NAME = ").append(quoteName(fe.name))
              .append(", FILENAME = '").append(sparsePath.replace("'", "''")).append("')");
        }
        sb.append(" AS SNAPSHOT OF [").append(escapeBracket(dbName)).append("]");

        try (Statement st = masterConn.createStatement()) {
            st.execute(sb.toString());
        }
    }

    /**
     * Drop a single database snapshot. Best-effort: kills connections first.
     */
    private void dropSnapshot(Connection masterConn, String snapName) throws Exception {
        // Kill any lingering connections to the snapshot
        try (Statement st = masterConn.createStatement()) {
            st.execute("ALTER DATABASE [" + escapeBracket(snapName)
                    + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
        } catch (Exception ignored) {}
        try (Statement st = masterConn.createStatement()) {
            st.execute("DROP DATABASE [" + escapeBracket(snapName) + "]");
        }
    }

    /**
     * Drop any snapshot databases left from a previous crashed run.
     */
    private void cleanOrphanSnapshots(Connection masterConn) throws Exception {
        List<String> orphans = new ArrayList<>();
        String sql = "SELECT name FROM sys.databases WHERE name LIKE '%" +
                     SNAP_SUFFIX.replace("_", "\\_") + "' ESCAPE '\\'";
        try (Statement st = masterConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name.endsWith(SNAP_SUFFIX)) {
                    orphans.add(name);
                }
            }
        }
        for (String snap : orphans) {
            try {
                dropSnapshot(masterConn, snap);
                Log.info(log, "Cleaned up orphan snapshot", "snapshot", snap);
            } catch (Exception e) {
                Log.warn(log, "Failed to clean orphan snapshot", "snapshot", snap, "error", e.getMessage());
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** Quote a SQL Server identifier name with brackets. */
    private static String quoteName(String name) {
        return "[" + escapeBracket(name) + "]";
    }

    private record FileEntry(String name, String typeDesc) {}
}
