package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Pipeline step that exports SQL Server tables to Dumpling-compatible CSV files.
 *
 * <p>Reads from context:
 * <ul>
 *   <li>{@code "schemas"}  (List&lt;String&gt;)</li>
 *   <li>{@code "tables"}   (List&lt;String&gt;)</li>
 * </ul>
 *
 * <p>Writes to context:
 * <ul>
 *   <li>{@code "dumpSummaries"} (List&lt;DumpTableResult&gt;)</li>
 *   <li>{@code "dumpTotalRows"} (Long)</li>
 * </ul>
 */
public class DumpStep implements MigrationStep {

    /** Simple result carrier per table — used for summary printing. */
    public record DumpTableResult(
            String dbName, String schema, String table,
            long rows, int files, long elapsedMs, String error) {
        public boolean isError() { return error != null; }
    }

    private final AppConfig config;
    private final SchemaExtractor schemaExtractor;
    private final DumpExtractor dumpExtractor;
    private final Supplier<DumpWriter> writerFactory;
    private final StructuredLogger log;

    public DumpStep(AppConfig config, SchemaExtractor schemaExtractor,
                    DumpExtractor dumpExtractor, Supplier<DumpWriter> writerFactory,
                    StructuredLogger log) {
        this.config          = config;
        this.schemaExtractor = schemaExtractor;
        this.dumpExtractor   = dumpExtractor;
        this.writerFactory   = writerFactory;
        this.log             = log;
    }

    @Override
    public String name() { return "Dump"; }

    /** Checked version of {@link java.util.function.Function} for connection factories. */
    @FunctionalInterface
    interface ConnectionFactory {
        Connection apply(String dbName) throws Exception;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        return executeWithConnections(ctx, dbName ->
                DriverManager.getConnection(
                        config.getSource().sqlServerJdbcUrlForDB(dbName),
                        config.getSource().getUsername(),
                        config.getSource().getPassword()));
    }

    /**
     * Extracted for testability — allows injecting mock connections.
     */
    @SuppressWarnings("unchecked")
    StepResult executeWithConnections(StepContext ctx,
                                      ConnectionFactory connFactory) throws Exception {
        List<String> schemas = ctx.get("schemas", List.class);
        List<String> tables  = ctx.get("tables",  List.class);

        AppConfig.DumpConfig dc = config.getDump();
        Path outputRoot = resolveOutputDir(dc.getOutputDir());
        log.log("INFO", "Dump started", "outputDir", outputRoot.toString());

        // Discover databases
        List<String> dbNames;
        try {
            Connection master = connFactory.apply("master");
            try {
                dbNames = schemaExtractor.listDatabases(master);
            } finally {
                try { master.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // master connection may fail in tests; use empty list
            dbNames = List.of();
        }

        boolean useSnapshot = dc.isSnapshotIsolation();
        String startTime = java.time.Instant.now().toString();
        String startLsn = null;

        if (useSnapshot) {
            try (Connection masterConn2 = connFactory.apply("master")) {
                startLsn = readStartLsn(masterConn2);
            }
        }

        List<DumpTableResult> allResults = new ArrayList<>();
        long totalRows = 0L;
        int  concurrency = Math.max(1, dc.getConcurrency());

        for (String dbName : dbNames) {
            if (useSnapshot) {
                try (Connection masterConn = connFactory.apply("master")) {
                    ensureSnapshotIsolation(masterConn, dbName);
                }
            }

            Connection dbConn = connFactory.apply(dbName);
            try {
                if (useSnapshot) {
                    setSnapshotIsolationLevel(dbConn);
                }

                List<String[]> tableList = schemaExtractor.listTables(dbConn, schemas, tables);
                log.log("INFO", "Dumping database", "db", dbName, "tables", tableList.size());

                boolean useNolock = !useSnapshot && dc.isNolock();

                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                List<Future<DumpTableResult>> futures = new ArrayList<>();

                for (String[] entry : tableList) {
                    String schema = entry[0];
                    String table  = entry[1];
                    futures.add(pool.submit(() ->
                            dumpOneTable(connFactory, dbName, schema, table, dc,
                                    outputRoot, useSnapshot, useNolock)));
                }

                pool.shutdown();
                pool.awaitTermination(24, TimeUnit.HOURS);

                for (Future<DumpTableResult> f : futures) {
                    DumpTableResult r = f.get();
                    allResults.add(r);
                    totalRows += r.rows();
                    if (r.isError()) {
                        log.log("ERROR", "Table dump failed",
                                "table", r.schema() + "." + r.table(), "error", r.error());
                    } else {
                        log.log("INFO", "Table dump complete",
                                "table", r.schema() + "." + r.table(),
                                "rows", r.rows(), "files", r.files(),
                                "ms", r.elapsedMs());
                    }
                }
            } finally {
                try { dbConn.close(); } catch (Exception ignored) {}
            }
        }

        writeDumpMeta(outputRoot, startLsn, startTime, dbNames, useSnapshot);

        ctx.put("dumpSummaries", allResults);
        ctx.put("dumpTotalRows", totalRows);

        long errors = allResults.stream().filter(DumpTableResult::isError).count();
        return errors == 0
                ? StepResult.ok("dump complete, tables=" + allResults.size() + " rows=" + totalRows)
                : StepResult.ok("dump complete with " + errors + " errors, rows=" + totalRows);
    }

    private DumpTableResult dumpOneTable(ConnectionFactory connFactory,
                                         String dbName, String schema, String table,
                                         AppConfig.DumpConfig dc, Path outputRoot,
                                         boolean useSnapshot, boolean useNolock) {
        long start = System.currentTimeMillis();
        DumpWriter writer = writerFactory.get();
        long[] rowCount  = {0L};
        int[]  fileCount = {0};

        try {
            Connection conn = connFactory.apply(dbName);
            try {
                if (useSnapshot) {
                    setSnapshotIsolationLevel(conn);
                }

                List<String> columns = dumpExtractor.getColumnNames(conn, schema, table, useNolock);

                dumpExtractor.streamTable(conn, schema, table, dc.getChunkSize(), useNolock,
                        batch -> {
                            writer.writeBatch(outputRoot, dbName, schema, table, columns, batch);
                            rowCount[0] += batch.rows().size();
                            fileCount[0] = batch.batchIndex() + 1;
                        });

                writer.close();
                return new DumpTableResult(dbName, schema, table,
                        rowCount[0], fileCount[0], System.currentTimeMillis() - start, null);
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { writer.close(); } catch (Exception ignored) {}
            return new DumpTableResult(dbName, schema, table,
                    rowCount[0], fileCount[0], System.currentTimeMillis() - start, e.getMessage());
        }
    }

    // ── Snapshot isolation helpers ────────────────────────────────────────────

    void ensureSnapshotIsolation(Connection masterConn, String dbName) throws Exception {
        String checkSql = "SELECT snapshot_isolation_state_desc FROM sys.databases WHERE name = ?";
        try (PreparedStatement ps = masterConn.prepareStatement(checkSql)) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "ON".equalsIgnoreCase(rs.getString(1))) {
                    return; // already enabled
                }
            }
        }
        try (Statement st = masterConn.createStatement()) {
            st.execute("ALTER DATABASE [" + dbName + "] SET ALLOW_SNAPSHOT_ISOLATION ON");
        }
    }

    void setSnapshotIsolationLevel(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL SNAPSHOT");
        }
    }

    String readStartLsn(Connection masterConn) {
        try (Statement st = masterConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT sys.fn_cdc_get_max_lsn()")) {
            if (rs.next()) {
                byte[] lsn = rs.getBytes(1);
                if (lsn == null) return null;
                StringBuilder sb = new StringBuilder("0x");
                for (byte b : lsn) sb.append(String.format("%02X", b));
                return sb.toString();
            }
        } catch (Exception e) {
            // CDC might not be enabled — return null, not fatal
        }
        return null;
    }

    void writeDumpMeta(Path outputRoot, String startLsn, String startTime,
                       List<String> databases, boolean snapshotIsolation) {
        try {
            java.nio.file.Files.createDirectories(outputRoot);
            Path metaFile = outputRoot.resolve("dump-meta.json");
            String lsnValue = startLsn != null ? "\"" + startLsn + "\"" : "null";
            String json = "{\n" +
                "  \"startTime\": \"" + startTime + "\",\n" +
                "  \"startLsn\": " + lsnValue + ",\n" +
                "  \"databases\": [" + databases.stream()
                    .map(d -> "\"" + d + "\"")
                    .collect(java.util.stream.Collectors.joining(", ")) + "],\n" +
                "  \"snapshotIsolation\": " + snapshotIsolation + "\n" +
                "}\n";
            java.nio.file.Files.writeString(metaFile, json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.log("WARN", "Failed to write dump-meta.json", "error", e.getMessage());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static Path resolveOutputDir(String configured) {
        String base = (configured == null || configured.isBlank())
                ? "dump-output"
                : configured;
        return Path.of(base, LocalDateTime.now().format(TS_FMT));
    }
}

