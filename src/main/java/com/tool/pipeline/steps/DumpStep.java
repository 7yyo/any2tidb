package com.tool.pipeline.steps;

import com.tool.common.FilterUtils;
import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.extractor.PkRange;
import com.tool.dump.writer.CsvDumpWriter;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.snapshot.SnapshotConfig;
import com.tool.source.ConsistencyProvider;
import com.tool.source.SourceDriver;
import static com.tool.source.sqlserver.SqlServerCdcUtils.captureLsn;
import static com.tool.source.sqlserver.SqlServerCdcUtils.hexLsnToDebezium;
import static com.tool.source.sqlserver.SqlServerCdcUtils.writeDebeziumOffset;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Pipeline step that exports source database tables to Dumpling-compatible CSV files.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>NOLOCK</b> (default) — per-table parallel export, no consistency guarantees</li>
 *   <li><b>Snapshot</b> ({@code --consistency=true}) — database-level consistent
 *       point-in-time via SQL Server Database Snapshots. Tables are split by PK range
 *       for intra-table parallelism; all chunks share a single thread pool.</li>
 * </ul>
 *
 * <p>Reads from context:
 * <ul>
 *   <li>{@code "databases"}            (List&lt;String&gt;)</li>
 *   <li>{@code "tables"}               (List&lt;String&gt;)</li>
 *   <li>{@code "dumpOutputDir"}        (String)</li>
 *   <li>{@code "dumpFileSizeMb"}       (Integer)</li>
 *   <li>{@code "dumpChunkSize"}        (Integer)</li>
 *   <li>{@code "dumpSnapshot"}        (Boolean)</li>
 *   <li>{@code "dumpConcurrency"}      (Integer)</li>
 * </ul>
 *
 * <p>Writes to context:
 * <ul>
 *   <li>{@code "dumpSummaries"}     (List&lt;DumpTableResult&gt;)</li>
 *   <li>{@code "dumpTotalRows"}     (Long)</li>
 *   <li>{@code "dumpStartLsnByDb"}  (Map&lt;String,String&gt; — dbName → LSN for CDC start point)</li>
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
    private final ConsistencyProvider consistency;
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(DumpStep.class);

    public DumpStep(AppConfig config, SchemaExtractor schemaExtractor,
                    DumpExtractor dumpExtractor, Supplier<DumpWriter> writerFactory,
                    ConsistencyProvider consistency, SourceDriver sourceDriver) {
        this.config          = config;
        this.schemaExtractor = schemaExtractor;
        this.dumpExtractor   = dumpExtractor;
        this.writerFactory   = writerFactory;
        this.consistency     = consistency;
        this.sourceDriver    = sourceDriver;
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
                        sourceDriver.buildJdbcUrlTo(config.getSource(), dbName),
                        config.getSource().getUsername(),
                        config.getSource().getPassword()));
    }

    /**
     * Extracted for testability — allows injecting mock connections.
     */
    @SuppressWarnings("unchecked")
    StepResult executeWithConnections(StepContext ctx,
                                      ConnectionFactory connFactory) throws Exception {
        List<String> databases = ctx.get("databases", List.class);
        List<String> tables  = ctx.get("tables",  List.class);

        String outputDir          = ctx.get("dumpOutputDir",        String.class);
        int chunkSize             = ctx.get("dumpChunkSize",        Integer.class);
        int fileSizeMb            = ctx.get("dumpFileSizeMb",       Integer.class);
        boolean useSnapshot = Boolean.TRUE.equals(ctx.get("dumpSnapshot", Boolean.class));
        boolean nolock      = !useSnapshot;
        int concurrency           = ctx.get("dumpConcurrency",      Integer.class);

        Path outputRoot = resolveOutputDir(outputDir);
        Log.info(log, "Dump started", "outputDir", outputRoot.toString(),
                "mode", useSnapshot ? "snapshot" : "nolock");

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
            Log.warn(log, "Failed to list databases, no tables will be exported",
                    "error", e.getMessage());
            dbNames = List.of();
        }

        int totalDbs = dbNames.size();
        dbNames = FilterUtils.filterNames(dbNames, databases);
        if (databases != null && !databases.isEmpty()) {
            Log.info(log, "Filtered databases", "before", totalDbs, "after", dbNames.size(),
                    "filter", databases);
            if (dbNames.isEmpty()) {
                Log.warn(log, "--databases filter matched nothing, check spelling",
                        "filter", databases);
            }
        }

        String startTime = java.time.Instant.now().toString();
        Map<String, String> startLsnByDb = new LinkedHashMap<>();

        List<DumpTableResult> allResults = new ArrayList<>();
        long totalRows = 0L;
        long dumpStartMs = System.currentTimeMillis();

        long threshold = fileSizeMb <= 0
                ? Long.MAX_VALUE : (long) fileSizeMb * 1024 * 1024;

        if (useSnapshot) {
            // Database Snapshot mode: consistent, parallel inter-table + intra-table
            allResults = dumpWithSnapshots(ctx, connFactory, dbNames,
                    tables, chunkSize, threshold, concurrency, outputRoot,
                    startLsnByDb);
            for (DumpTableResult r : allResults) {
                totalRows += r.rows();
            }
        } else {
            // Parallel per-table, no consistency
            concurrency = Math.max(1, concurrency);
            for (String dbName : dbNames) {
                // Capture CDC LSN before dump for sync continuity
                try {
                    String lsn = captureLsn(
                            config.getSource().getHost(), config.getSource().getPort(),
                            config.getSource().getUsername(), config.getSource().getPassword(),
                            dbName);
                    startLsnByDb.put(dbName, lsn);
                    Log.info(log, "CDC LSN captured", "database", dbName, "lsn", lsn);
                } catch (Exception e) {
                    Log.warn(log, "CDC LSN capture failed, sync from this point will not be possible",
                            "database", dbName, "error", e.getMessage());
                }
                List<DumpTableResult> dbResults = dumpDbParallel(connFactory, dbName,
                        tables, chunkSize, outputRoot, nolock, concurrency);
                for (DumpTableResult r : dbResults) {
                    allResults.add(r);
                    totalRows += r.rows();
                }
            }
        }

        writeDumpMeta(outputRoot, startLsnByDb, startTime, dbNames, useSnapshot);
        // Write Debezium offset files for sync continuity from NOLOCK dumps
        writeOffsetsForSync(outputRoot, startLsnByDb);

        ctx.put("dumpSummaries",    allResults);
        ctx.put("dumpTotalRows",    totalRows);
        ctx.put("dumpStartLsnByDb", startLsnByDb);

        long errors = allResults.stream().filter(DumpTableResult::isError).count();
        long totalFiles = allResults.stream().mapToLong(DumpTableResult::files).sum();
        long totalMs = System.currentTimeMillis() - dumpStartMs;
        Log.info(log, "Dump finished",
                "tables", allResults.size(),
                "rows", totalRows,
                "files", totalFiles,
                "errors", errors,
                "ms", totalMs);
        return errors == 0
                ? StepResult.ok("dump complete, tables=" + allResults.size() + " rows=" + totalRows)
                : StepResult.ok("dump complete with " + errors + " errors, rows=" + totalRows);
    }

    // ── Database Snapshot mode ───────────────────────────────────────────────

    private List<DumpTableResult> dumpWithSnapshots(StepContext ctx,
                                                     ConnectionFactory connFactory,
                                                     List<String> dbNames,
                                                     List<String> tablesFilter,
                                                     int chunkSize,
                                                     long threshold,
                                                     int concurrency,
                                                     Path outputRoot,
                                                     Map<String, String> startLsnByDb) {
        List<DumpTableResult> allResults = new ArrayList<>();
        List<String> allSnapNames = new ArrayList<>();

        Connection masterConn = null;
        try {
            masterConn = connFactory.apply("master");

            // 1. Check prerequisites (Enterprise/Developer edition)
            consistency.checkPrerequisites(masterConn);

            // 2. Create all snapshots + capture CDC start LSNs
            Map<String, ConsistencyProvider.SnapshotInfo> snapMap =
                    consistency.createSnapshots(masterConn, dbNames, outputRoot);
            Map<String, String> snapNameMap = new LinkedHashMap<>();
            for (var entry : snapMap.entrySet()) {
                String dbName = entry.getKey();
                ConsistencyProvider.SnapshotInfo info = entry.getValue();
                snapNameMap.put(dbName, info.snapName());
                startLsnByDb.put(dbName, info.lsn());
                allSnapNames.add(info.snapName());
            }
            Log.info(log, "Database snapshots created", "count", allSnapNames.size());

            // 3. Discover all PK-range work items from snapshot databases
            List<PkRange> allRanges = new ArrayList<>();
            for (String dbName : dbNames) {
                String snapName = snapNameMap.get(dbName);
                if (snapName == null) continue;

                String snapUrl = consistency.jdbcUrlForSnapshot(dbName, snapName);
                try (Connection snapConn = DriverManager.getConnection(snapUrl,
                        config.getSource().getUsername(), config.getSource().getPassword())) {
                    List<String[]> tableList = schemaExtractor.listTables(snapConn,
                            tablesFilter);
                    for (String[] entry : tableList) {
                        String schema = entry[0];
                        String table = entry[1];
                        List<PkRange> ranges = dumpExtractor.computePkRanges(
                                snapConn, dbName, schema, table, chunkSize);
                        allRanges.addAll(ranges);
                    }
                }
            }

            Log.info(log, "Dump work items computed",
                    "totalChunks", allRanges.size(),
                    "concurrency", concurrency);

            // 4. Submit all work items to shared thread pool.
            //    Each TABLE gets one shared CsvDumpWriter — chunks of the same
            //    table write to the same CSV file until it reaches the size threshold.
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            Map<String, CsvDumpWriter> writersByTable = new ConcurrentHashMap<>();
            List<Future<DumpTableResult>> futures = new ArrayList<>();
            boolean continueOnError = Boolean.TRUE.equals(
                    ctx.get("continueOnError", Boolean.class));

            for (PkRange range : allRanges) {
                String tableKey = range.dbName() + "." + range.schema() + "." + range.table();
                CsvDumpWriter writer = writersByTable.computeIfAbsent(tableKey,
                        k -> new CsvDumpWriter(threshold));
                futures.add(pool.submit(() ->
                        dumpPkRange(range, snapNameMap, chunkSize, writer, outputRoot)));
            }

            pool.shutdown();
            pool.awaitTermination(48, TimeUnit.HOURS);

            // Collect per-chunk results, aggregate per table
            Map<String, DumpTableResult> tableAgg = new LinkedHashMap<>();
            for (Future<DumpTableResult> f : futures) {
                DumpTableResult r = f.get();
                if (r.isError()) {
                    allResults.add(r);
                    Log.error(log, "Chunk dump failed",
                            "database", r.dbName(),
                            "table", r.schema() + "." + r.table(),
                            "error", r.error());
                } else {
                    String tKey = r.dbName() + "." + r.schema() + "." + r.table();
                    DumpTableResult prev = tableAgg.get(tKey);
                    long rows = (prev != null ? prev.rows() : 0) + r.rows();
                    long maxMs = Math.max(prev != null ? prev.elapsedMs() : 0, r.elapsedMs());
                    tableAgg.put(tKey, new DumpTableResult(
                            r.dbName(), r.schema(), r.table(), rows, 0, maxMs, null));
                }
            }

            // Close all writers and set file counts on aggregated results
            for (var entry : writersByTable.entrySet()) {
                CsvDumpWriter w = entry.getValue();
                w.close();
                DumpTableResult agg = tableAgg.get(entry.getKey());
                if (agg != null && agg.rows() > 0) {
                    DumpTableResult result = new DumpTableResult(
                            agg.dbName(), agg.schema(), agg.table(),
                            agg.rows(), w.getFileCount(), agg.elapsedMs(), null);
                    tableAgg.put(entry.getKey(), result);
                    Log.info(log, "Table dump complete",
                            "database", agg.dbName(),
                            "table", agg.schema() + "." + agg.table(),
                            "rows", agg.rows(), "files", w.getFileCount(),
                            "ms", agg.elapsedMs());
                }
            }
            allResults.addAll(tableAgg.values());

        } catch (Exception e) {
            Log.error(log, "Snapshot dump failed", "databases", dbNames, "error", e.getMessage());
        } finally {
            // 5. Drop all snapshots (best-effort)
            if (masterConn != null) {
                try {
                    consistency.dropSnapshots(masterConn, allSnapNames);
                } catch (Exception e) {
                    Log.warn(log, "Failed to drop snapshots", "error", e.getMessage());
                }
                try { masterConn.close(); } catch (Exception ignored) {}
            }
        }

        return allResults;
    }

    /**
     * Dump a single PK-range chunk to CSV.
     * Writes to the shared {@code writer} (which may be shared across chunks of the
     * same table). The writer is NOT closed here — the caller manages its lifecycle.
     */
    private DumpTableResult dumpPkRange(PkRange range, Map<String, String> snapMap,
                                         int chunkSize, DumpWriter writer, Path outputRoot) {
        long start = System.currentTimeMillis();
        String dbName = range.dbName();
        String schema = range.schema();
        String table = range.table();
        long[] rowCount = {0L};

        try {
            String snapDbName = snapMap.get(dbName);
            String snapUrl = consistency.jdbcUrlForSnapshot(dbName, snapDbName);
            Connection snapConn = DriverManager.getConnection(snapUrl,
                    config.getSource().getUsername(), config.getSource().getPassword());
            try {
                List<String> columns = dumpExtractor.getColumnNames(snapConn, schema, table, false);
                dumpExtractor.streamTableRange(snapConn, range, chunkSize, batch -> {
                    writer.writeBatch(outputRoot, dbName, schema, table, columns, batch);
                    rowCount[0] += batch.rows().size();
                });
                long elapsed = System.currentTimeMillis() - start;
                Log.info(log, "Chunk dump complete",
                        "database", dbName,
                        "table", schema + "." + table,
                        "chunk", range.chunkIndex(),
                        "rows", rowCount[0], "ms", elapsed);
                return new DumpTableResult(dbName, schema, table,
                        rowCount[0], 0, elapsed, null);
            } finally {
                try { snapConn.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return new DumpTableResult(dbName, schema, table,
                    rowCount[0], 0, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    // ── NOLOCK mode: parallel per-table ─────────────────────────────────────────

    private List<DumpTableResult> dumpDbParallel(ConnectionFactory connFactory,
                                                  String dbName,
                                                  List<String> tables,
                                                  int chunkSize,
                                                  Path outputRoot,
                                                  boolean nolock,
                                                  int concurrency) {
        List<DumpTableResult> allResults = new ArrayList<>();

        try {
            Connection dbConn = connFactory.apply(dbName);
            List<String[]> tableList;
            try {
                tableList = schemaExtractor.listTables(dbConn, tables);
            } finally {
                try { dbConn.close(); } catch (Exception ignored) {}
            }

            Log.info(log, "Dumping database (nolock)", "db", dbName,
                    "tables", tableList.size(), "concurrency", concurrency);

            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<Future<DumpTableResult>> futures = new ArrayList<>();

            for (String[] entry : tableList) {
                String schema = entry[0];
                String table  = entry[1];
                futures.add(pool.submit(() ->
                        dumpTableWithNewConn(connFactory, dbName, schema, table,
                                chunkSize, outputRoot, nolock)));
            }

            pool.shutdown();
            pool.awaitTermination(24, TimeUnit.HOURS);

            for (Future<DumpTableResult> f : futures) {
                allResults.add(f.get());
            }
        } catch (Exception e) {
            Log.error(log, "Database dump failed", "db", dbName, "error", e.getMessage());
        }

        return allResults;
    }

    // ── NOLOCK per-table helper ──────────────────────────────────────────────

    /** Dump one table via a new connection (for NOLOCK/parallel mode). */
    private DumpTableResult dumpTableWithNewConn(ConnectionFactory connFactory,
                                                  String dbName, String schema, String table,
                                                  int chunkSize, Path outputRoot,
                                                  boolean useNolock) {
        long start = System.currentTimeMillis();
        DumpWriter writer = writerFactory.get();
        long[] rowCount = {0L};

        try {
            Connection conn = connFactory.apply(dbName);
            try {
                List<String> columns = dumpExtractor.getColumnNames(conn, schema, table, useNolock);

                dumpExtractor.streamTable(conn, schema, table, chunkSize, useNolock,
                        batch -> {
                            writer.writeBatch(outputRoot, dbName, schema, table, columns, batch);
                            rowCount[0] += batch.rows().size();
                        });

                writer.close();
                int files = writer.getFileCount();
                long elapsed = System.currentTimeMillis() - start;
                Log.info(log, "Table dump complete",
                        "database", dbName,
                        "table", schema + "." + table,
                        "rows", rowCount[0], "files", files, "ms", elapsed);
                return new DumpTableResult(dbName, schema, table,
                        rowCount[0], files, elapsed, null);
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { writer.close(); } catch (Exception ignored) {}
            long elapsed = System.currentTimeMillis() - start;
            Log.error(log, "Table dump failed",
                    "database", dbName,
                    "table", schema + "." + table, "error", e.getMessage());
            return new DumpTableResult(dbName, schema, table,
                    rowCount[0], 0, elapsed, e.getMessage());
        }
    }

    // ── dump-meta.json ───────────────────────────────────────────────────────

    void writeDumpMeta(Path outputRoot, Map<String, String> startLsnByDb, String startTime,
                       List<String> databases, boolean snapshotMode) {
        try {
            java.nio.file.Files.createDirectories(outputRoot);
            Path metaFile = outputRoot.resolve("dump-meta.json");
            StringBuilder lsnEntries = new StringBuilder();
            for (Map.Entry<String, String> e : startLsnByDb.entrySet()) {
                if (lsnEntries.length() > 0) lsnEntries.append(",\n");
                lsnEntries.append("    ").append(jsonEscape(e.getKey()))
                        .append(": ").append(jsonEscape(e.getValue()));
            }
            String json = "{\n" +
                "  \"startTime\": " + jsonEscape(startTime) + ",\n" +
                "  \"startLsnByDb\": {\n" + lsnEntries + "\n  },\n" +
                "  \"databases\": [" + databases.stream()
                    .map(DumpStep::jsonEscape)
                    .collect(java.util.stream.Collectors.joining(", ")) + "],\n" +
                "  \"snapshotMode\": " + snapshotMode + "\n" +
                "}\n";
            java.nio.file.Files.writeString(metaFile, json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn(log, "Failed to write dump-meta.json", "error", e.getMessage());
        }
    }

    /**
     * Write Debezium-compatible offset files for each database that had LSN captured.
     * Uses the same default directory as SnapshotStep ({@value SnapshotConfig#DEFAULT_OFFSET_STORAGE_PATH})
     * so both dump and snapshot modes share a single offset location for sync.
     */
    private void writeOffsetsForSync(Path outputRoot, Map<String, String> startLsnByDb) {
        Path offsetDir = Path.of(SnapshotConfig.DEFAULT_OFFSET_STORAGE_PATH);
        for (var entry : startLsnByDb.entrySet()) {
            String dbName = entry.getKey();
            String hexLsn = entry.getValue();
            try {
                String debeziumLsn = hexLsnToDebezium(hexLsn);
                String path = offsetDir.resolve(dbName + ".offset").toString();
                writeDebeziumOffset(path, dbName, debeziumLsn);
                Log.info(log, "Debezium offset written", "database", dbName,
                        "path", path, "lsn", debeziumLsn);
            } catch (Exception e) {
                Log.warn(log, "Failed to write Debezium offset", "database", dbName,
                        "error", e.getMessage());
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static Path resolveOutputDir(String configured) {
        String base = (configured == null || configured.isBlank())
                ? "dump-output"
                : configured;
        return Path.of(base);
    }

    static String jsonEscape(String s) {
        if (s == null) return "\"null\"";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default  -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
