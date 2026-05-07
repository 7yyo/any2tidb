package com.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed metadata store. One instance per TaskManager.
 *
 * The DB file lives at {@code tasksRoot/any2tidb.db}. WAL mode is
 * enabled for crash safety and concurrent read/write within a single
 * JVM. Write methods are synchronized for thread safety.
 */
class DbManager {

    private static final Logger log = LoggerFactory.getLogger(DbManager.class);

    private final Path dbPath;
    private Connection conn;

    DbManager(Path tasksRoot) {
        this.dbPath = tasksRoot.resolve("any2tidb.db");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    synchronized void ensureInitialized() throws SQLException {
        if (conn != null) return;
        dbPath.getParent().toFile().mkdirs();
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        createSchema();
        migrateFromJsonFiles();
    }

    void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        conn = null;
    }

    /** Delete the entire database file. Must call {@link #close()} first. */
    void deleteDatabase() throws Exception {
        Files.deleteIfExists(dbPath);
        // also delete WAL/SHM if present
        Files.deleteIfExists(Path.of(dbPath + "-wal"));
        Files.deleteIfExists(Path.of(dbPath + "-shm"));
    }

    // ── Schema ────────────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS task (
                        task        TEXT PRIMARY KEY,
                        mode        TEXT NOT NULL,
                        status      TEXT NOT NULL DEFAULT 'RUNNING',
                        source_type TEXT,
                        source_host TEXT,
                        source_port INTEGER,
                        source_db   TEXT,
                        target_type TEXT,
                        target_host TEXT,
                        target_port INTEGER,
                        target_db   TEXT,
                        from_task   TEXT,
                        tables      INTEGER,
                        error       TEXT,
                        pid         TEXT,
                        created_at  TEXT NOT NULL,
                        started_at  TEXT,
                        finished_at TEXT
                    )
                    """);
            // Migrate: add pid column for databases created before this feature
            try { st.execute("ALTER TABLE task ADD COLUMN pid TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE task_archive ADD COLUMN pid TEXT"); } catch (SQLException ignored) {}
            st.execute("""
                    CREATE TABLE IF NOT EXISTS snapshot_result (
                        task    TEXT NOT NULL REFERENCES task(task),
                        db_name TEXT NOT NULL,
                        tables  INTEGER NOT NULL DEFAULT 0,
                        rows    INTEGER NOT NULL DEFAULT 0,
                        error   TEXT,
                        PRIMARY KEY (task, db_name)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dump_result (
                        task      TEXT NOT NULL REFERENCES task(task),
                        db_name   TEXT NOT NULL,
                        tables    INTEGER NOT NULL DEFAULT 0,
                        rows      INTEGER NOT NULL DEFAULT 0,
                        start_lsn TEXT,
                        PRIMARY KEY (task, db_name)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS task_archive (
                        task        TEXT NOT NULL,
                        mode        TEXT NOT NULL,
                        status      TEXT NOT NULL,
                        source_type TEXT,
                        source_host TEXT,
                        source_port INTEGER,
                        source_db   TEXT,
                        target_type TEXT,
                        target_host TEXT,
                        target_port INTEGER,
                        target_db   TEXT,
                        from_task   TEXT,
                        tables      INTEGER,
                        error       TEXT,
                        pid         TEXT,
                        created_at  TEXT NOT NULL,
                        started_at  TEXT,
                        finished_at TEXT,
                        archived_at TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS task_history (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        task       TEXT NOT NULL,
                        action     TEXT NOT NULL,
                        details    TEXT,
                        created_at TEXT NOT NULL
                    )
                    """);
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    synchronized void insert(TaskMeta m) throws SQLException {
        // archive old DELETED row before overwriting
        TaskMeta old = findByTask(m.getTask());
        if (old != null && "DELETED".equals(old.getStatus())) {
            archive(old);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM task WHERE task=?")) {
                ps.setString(1, m.getTask());
                ps.executeUpdate();
            }
        }
        String sql = """
                INSERT INTO task
                (task, mode, status, source_type, source_host, source_port, source_db,
                 target_type, target_host, target_port, target_db,
                 from_task, tables, error, pid, created_at, started_at, finished_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?,?, ?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, m);
            ps.executeUpdate();
        }
    }

    private void archive(TaskMeta m) throws SQLException {
        String sql = """
                INSERT INTO task_archive
                (task, mode, status, source_type, source_host, source_port, source_db,
                 target_type, target_host, target_port, target_db,
                 from_task, tables, error, pid, created_at, started_at, finished_at, archived_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?,?, ?,?,?,?,?,?,?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, m);
            ps.setString(19, java.time.OffsetDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")));
            ps.executeUpdate();
        }
    }

    synchronized void update(TaskMeta m) throws SQLException {
        ensureInitialized();
        String sql = """
                UPDATE task SET
                    task=?, mode=?, status=?, source_type=?, source_host=?, source_port=?, source_db=?,
                    target_type=?, target_host=?, target_port=?, target_db=?,
                    from_task=?, tables=?, error=?, pid=?, created_at=?, started_at=?, finished_at=?
                WHERE task=?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, m);
            ps.setString(19, m.getTask());
            ps.executeUpdate();
        }
    }

    synchronized void markDeleted(String taskName) throws SQLException {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE task SET status='DELETED', finished_at=? WHERE task=?")) {
            ps.setString(1, java.time.OffsetDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")));
            ps.setString(2, taskName);
            ps.executeUpdate();
        }
    }

    TaskMeta findByTask(String taskName) throws SQLException {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM task WHERE task=?")) {
            ps.setString(1, taskName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    List<TaskMeta> findAll() throws SQLException {
        ensureInitialized();
        List<TaskMeta> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM task WHERE status != 'DELETED' ORDER BY created_at ASC")) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    List<TaskMeta> findDeleted() throws SQLException {
        ensureInitialized();
        List<TaskMeta> list = new ArrayList<>();
        // active soft-deleted tasks
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM task WHERE status = 'DELETED' ORDER BY finished_at DESC")) {
            while (rs.next()) list.add(map(rs));
        }
        // archived (overwritten) deletions
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT task, mode, status, source_type, source_host, source_port, source_db, "
                     + "target_type, target_host, target_port, target_db, "
                     + "from_task, tables, error, created_at, started_at, finished_at "
                     + "FROM task_archive ORDER BY archived_at DESC")) {
            while (rs.next()) {
                TaskMeta m = map(rs);
                m.setStatus("DELETED");
                list.add(m);
            }
        }
        return list;
    }

    boolean isEmpty() throws SQLException {
        ensureInitialized();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM task")) {
            return rs.getInt(1) == 0;
        }
    }

    // ── Snapshot / Dump results ────────────────────────────────────────────

    record SnapshotRow(String dbName, int tables, long rows, String error) {}
    record DumpRow(String dbName, int tables, long rows, String startLsn) {}

    synchronized void insertSnapshotResults(String task, List<SnapshotRow> results) throws SQLException {
        ensureInitialized();
        String sql = "INSERT OR REPLACE INTO snapshot_result(task, db_name, tables, rows, error) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SnapshotRow r : results) {
                ps.setString(1, task);
                ps.setString(2, r.dbName);
                ps.setInt(3, r.tables);
                ps.setLong(4, r.rows);
                ps.setString(5, r.error);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    List<SnapshotRow> findSnapshotResults(String task) throws SQLException {
        ensureInitialized();
        List<SnapshotRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT db_name, tables, rows, error FROM snapshot_result WHERE task=? ORDER BY db_name")) {
            ps.setString(1, task);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new SnapshotRow(
                        rs.getString("db_name"), rs.getInt("tables"),
                        rs.getLong("rows"), rs.getString("error")));
            }
        }
        return list;
    }

    synchronized void insertDumpResults(String task, List<DumpRow> results) throws SQLException {
        ensureInitialized();
        String sql = "INSERT OR REPLACE INTO dump_result(task, db_name, tables, rows, start_lsn) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DumpRow r : results) {
                ps.setString(1, task);
                ps.setString(2, r.dbName);
                ps.setInt(3, r.tables);
                ps.setLong(4, r.rows);
                ps.setString(5, r.startLsn);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    synchronized void deleteResults(String task) throws SQLException {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM snapshot_result WHERE task=?")) {
            ps.setString(1, task);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dump_result WHERE task=?")) {
            ps.setString(1, task);
            ps.executeUpdate();
        }
    }

    // ── History (audit trail) ────────────────────────────────────────────────

    record HistoryRow(long id, String task, String action, String details, String createdAt) {}

    synchronized void addHistory(String task, String action, String details) throws SQLException {
        ensureInitialized();
        String sql = "INSERT INTO task_history(task, action, details, created_at) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, task);
            ps.setString(2, action);
            ps.setString(3, details);
            ps.setString(4, java.time.OffsetDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")));
            ps.executeUpdate();
        }
    }

    List<HistoryRow> findHistory(String task) throws SQLException {
        ensureInitialized();
        List<HistoryRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, task, action, details, created_at FROM task_history WHERE task=? ORDER BY id")) {
            ps.setString(1, task);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new HistoryRow(
                        rs.getLong("id"), rs.getString("task"), rs.getString("action"),
                        rs.getString("details"), rs.getString("created_at")));
            }
        }
        return list;
    }

    // ── Bind / Map helpers ────────────────────────────────────────────────

    private static void bind(PreparedStatement ps, TaskMeta m) throws SQLException {
        TaskMeta.SourceInfo s = m.getSource();
        TaskMeta.TargetInfo t = m.getTarget();

        ps.setString(1, m.getTask());
        ps.setString(2, m.getMode());
        ps.setString(3, m.getStatus());
        ps.setString(4, s != null ? s.getType() : null);
        ps.setString(5, s != null ? s.getHost() : null);
        if (s != null && s.getPort() > 0) ps.setInt(6, s.getPort()); else ps.setNull(6, Types.INTEGER);
        ps.setString(7, s != null ? s.getDatabase() : null);
        ps.setString(8, t != null ? t.getType() : null);
        ps.setString(9, t != null ? t.getHost() : null);
        if (t != null && t.getPort() > 0) ps.setInt(10, t.getPort()); else ps.setNull(10, Types.INTEGER);
        ps.setString(11, t != null ? t.getDatabase() : null);
        ps.setString(12, m.getFromTask());
        if (m.getTables() != null) ps.setInt(13, m.getTables()); else ps.setNull(13, Types.INTEGER);
        ps.setString(14, m.getError());
        ps.setString(15, m.getPid());
        ps.setString(16, m.getCreatedAt());
        ps.setString(17, m.getStartedAt());
        ps.setString(18, m.getFinishedAt());
    }

    private static TaskMeta map(ResultSet rs) throws SQLException {
        TaskMeta m = new TaskMeta();
        m.setTask(rs.getString("task"));
        m.setMode(rs.getString("mode"));
        m.setStatus(rs.getString("status"));

        TaskMeta.SourceInfo s = new TaskMeta.SourceInfo();
        s.setType(rs.getString("source_type"));
        s.setHost(rs.getString("source_host"));
        s.setPort(rs.getInt("source_port"));
        s.setDatabase(rs.getString("source_db"));
        if (s.getType() != null) m.setSource(s);

        TaskMeta.TargetInfo t = new TaskMeta.TargetInfo();
        t.setType(rs.getString("target_type"));
        t.setHost(rs.getString("target_host"));
        t.setPort(rs.getInt("target_port"));
        t.setDatabase(rs.getString("target_db"));
        if (t.getType() != null) m.setTarget(t);

        m.setFromTask(rs.getString("from_task"));
        int tables = rs.getInt("tables");
        if (!rs.wasNull()) m.setTables(tables);
        m.setError(rs.getString("error"));
        m.setPid(rs.getString("pid"));
        m.setCreatedAt(rs.getString("created_at"));
        m.setStartedAt(rs.getString("started_at"));
        m.setFinishedAt(rs.getString("finished_at"));
        return m;
    }

    // ── Migration from legacy meta.json ───────────────────────────────────

    @SuppressWarnings("java:S1172") // tempDir only for testing override
    Path tasksRoot() { return dbPath.getParent(); }

    private void migrateFromJsonFiles() {
        try {
            if (!isEmpty()) return;
        } catch (SQLException e) {
            return;
        }
        Path root = tasksRoot();
        File[] dirs = root.toFile().listFiles(File::isDirectory);
        if (dirs == null) return;

        ObjectMapper mapper = new ObjectMapper();
        int count = 0;
        for (File dir : dirs) {
            File metaFile = new File(dir, "meta.json");
            if (!metaFile.exists()) continue;
            try {
                TaskMeta meta = mapper.readValue(metaFile, TaskMeta.class);
                insert(meta);
                metaFile.delete();
                count++;
            } catch (Exception e) {
                log.warn("Failed to migrate meta.json for '{}': {}", dir.getName(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("Migrated {} task(s) from meta.json to SQLite", count);
        }
    }
}
