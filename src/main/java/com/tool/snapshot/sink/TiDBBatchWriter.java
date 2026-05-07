package com.tool.snapshot.sink;

import static com.tool.common.SqlUtils.escapeBacktick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.logging.Log;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TiDBBatchWriter {

    private final DataSource dataSource;
    private final SinkRecordConverter converter;
    private final int batchSize;
    private final String dbName;
    private static final Logger log = LoggerFactory.getLogger(TiDBBatchWriter.class);
    private final ExecutorService flushExecutor;

    private final ConcurrentHashMap<String, List<Map<String, Object>>> buffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> fieldOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tableDbNames = new ConcurrentHashMap<>();
    private final AtomicLong totalRows = new AtomicLong(0L);
    private final long startMs = System.currentTimeMillis();

    // Per-table progress throttling
    private final ConcurrentHashMap<String, Long> tableLastLogRows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tableLastLogMs = new ConcurrentHashMap<>();
    private static final long TABLE_LOG_INTERVAL_MS = 60_000;


    private final ConcurrentHashMap<String, Long> tableRowCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tableErrorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tableStartMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> tableSampleCount = new ConcurrentHashMap<>();
    private static final int SAMPLE_MAX = 3;

    /** "table" → estimated total rows */
    private Map<String, Long> tableEstimates = Map.of();

    /** Tables that have already emitted a completion log */
    private final ConcurrentHashMap<String, Boolean> tableCompleted = new ConcurrentHashMap<>();

    private final List<Future<?>> flushFutures = new ArrayList<>();
    private int flushAddCount = 0;
    private static final int FLUSH_PURGE_INTERVAL = 128;

    public TiDBBatchWriter(DataSource dataSource, SinkRecordConverter converter, int batchSize,
                           int writerThreads, String dbName) {
        this.dataSource = dataSource;
        this.converter = converter;
        this.batchSize = batchSize;
        this.dbName = dbName;
        AtomicInteger n = new AtomicInteger(1);
        this.flushExecutor = Executors.newFixedThreadPool(writerThreads, r -> {
            Thread t = new Thread(r, "flush-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public void setTableEstimates(Map<String, Long> estimates) {
        this.tableEstimates = estimates;
    }

    public void accumulate(String dbName, String table, Map<String, Object> after) {
        tableDbNames.putIfAbsent(table, dbName);
        boolean firstRow = tableStartMs.putIfAbsent(table, System.currentTimeMillis()) == null;
        fieldOrders.computeIfAbsent(table,
                k -> new ArrayList<>(new LinkedHashMap<>(after).keySet()));

        totalRows.incrementAndGet();
        if (firstRow) {
            tableLastLogMs.put(table, System.currentTimeMillis());
        }
        tableRowCounts.merge(table, 1L, Long::sum);

        // Diagnose: log first N rows of each table with field types
        int sampled = tableSampleCount.getOrDefault(table, 0);
        if (sampled < SAMPLE_MAX) {
            tableSampleCount.put(table, sampled + 1);
            StringBuilder sb = new StringBuilder();
            for (var entry : new LinkedHashMap<>(after).entrySet()) {
                Object v = entry.getValue();
                sb.append(entry.getKey()).append("=(")
                  .append(v != null ? v.getClass().getSimpleName() : "null")
                  .append(")").append(v).append(", ");
            }
            Log.debug(log, "sample row", "table", dbName + "." + table,
                    "n", sampled + 1, "fields", sb.toString());
        }

        long now = System.currentTimeMillis();
        logTableProgress(table, now);

        // Buffer swap: when full, atomically replace with new list and flush async
        final List<Map<String, Object>>[] toFlush = new List[1];
        buffer.compute(table, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(after);
            if (list.size() >= batchSize) {
                toFlush[0] = list;
                return new ArrayList<>();
            }
            return list;
        });
        if (toFlush[0] != null) {
            String db = tableDbNames.get(table);
            List<String> cols = new ArrayList<>(fieldOrders.get(table));
            synchronized (flushFutures) {
                if ((++flushAddCount % FLUSH_PURGE_INTERVAL) == 0) {
                    flushFutures.removeIf(f -> f.isDone());
                }
                flushFutures.add(flushExecutor.submit(
                        () -> flushBatch(table, db, cols, toFlush[0])));
            }
        }
    }

    private synchronized void logTableProgress(String table, long now) {
        long rows = tableRowCounts.getOrDefault(table, 0L);
        long lastRows = tableLastLogRows.getOrDefault(table, 0L);
        long lastMs = tableLastLogMs.getOrDefault(table, 0L);
        if (now - lastMs < TABLE_LOG_INTERVAL_MS) return;

        tableLastLogRows.put(table, rows);
        tableLastLogMs.put(table, now);

        Long est = tableEstimates.get(table);
        long tableMs = now - tableStartMs.getOrDefault(table, now);
        double rps = tableMs > 0 ? rows * 1000.0 / tableMs : 0;

        String db = tableDbNames.getOrDefault(table, "?");
        List<Object> fields = new ArrayList<>();
        fields.add("db"); fields.add(db);
        fields.add("table"); fields.add(table);
        fields.add("rows"); fields.add(formatRows(rows) + (est != null && est > 0 ? "/" + formatRows(est) : ""));
        if (est != null && est > 0) {
            int pct = (int) (rows * 100 / est);
            fields.add("percent"); fields.add(pct + "%");
        }
        fields.add("speed"); fields.add(formatSpeed(rps));
        fields.add("elapsed"); fields.add(formatDuration(tableMs));
        if (est != null && est > 0 && rps > 0) {
            long remaining = est - rows;
            long etaMs = (long)(remaining * 1000.0 / rps);
            fields.add("remaining"); fields.add(formatDuration(etaMs));
        }
        Log.info(log, "table progress", fields.toArray());
    }


    public void flushAll() throws Exception {
        long tDrain = System.currentTimeMillis();
        // Drain remaining buffer entries
        int pendingBatches = 0;
        for (var entry : buffer.entrySet()) {
            String table = entry.getKey();
            List<Map<String, Object>> rows = buffer.replace(table, new ArrayList<>());
            if (rows == null || rows.isEmpty()) continue;
            String db = tableDbNames.getOrDefault(table, "?");
            List<String> cols = new ArrayList<>(fieldOrders.getOrDefault(table, List.of()));
            pendingBatches++;
            synchronized (flushFutures) {
                flushFutures.add(flushExecutor.submit(
                        () -> flushBatch(table, db, cols, rows)));
            }
        }
        long tAfterDrain = System.currentTimeMillis();

        // Wait for all flushes to complete, logging progress every 30s
        int totalFutures = flushFutures.size();
        long waitStartMs = System.currentTimeMillis();
        long lastLogMs = waitStartMs;
        int cursor = 0;
        while (cursor < flushFutures.size()) {
            Future<?> f = flushFutures.get(cursor);
            if (f.isDone()) {
                cursor++;
                continue;
            }
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            // Re-count remaining and log progress
            int nowRemaining = 0;
            for (int i = cursor; i < flushFutures.size(); i++) {
                if (!flushFutures.get(i).isDone()) nowRemaining++;
            }
            long elapsed = System.currentTimeMillis() - waitStartMs;
            if (nowRemaining > 0 && System.currentTimeMillis() - lastLogMs >= 30_000) {
                Log.info(log, "flushing",
                        "db", dbName,
                        "remaining", nowRemaining + " batches, ~" + formatRows(nowRemaining * batchSize) + " rows",
                        "elapsed", formatDuration(elapsed));
                lastLogMs = System.currentTimeMillis();
            }
        }
        long tAfterWait = System.currentTimeMillis();
        flushFutures.clear();
        flushExecutor.shutdown();
        flushExecutor.awaitTermination(1, TimeUnit.MINUTES);
        long tAfterTerm = System.currentTimeMillis();
        if (totalFutures > 0) {
            Log.info(log, "flushAll breakdown", "db", dbName,
                    "pendingBatches", pendingBatches,
                    "totalFutures", totalFutures,
                    "drainMs", tAfterDrain - tDrain,
                    "waitMs", tAfterWait - tAfterDrain,
                    "termMs", tAfterTerm - tAfterWait);
        }

        // Drain any remaining tables that didn't get a "last" signal
        long now = System.currentTimeMillis();
        for (String table : tableRowCounts.keySet()) {
            long rows = tableRowCounts.getOrDefault(table, 0L);
            if (rows > 0) {
                String db = tableDbNames.getOrDefault(table, "?");
                tableComplete(table, db);
            }
        }

        long totalErrors = tableErrorCounts.values().stream().mapToLong(Long::longValue).sum();
        if (totalErrors > 0) {
            Log.warn(log, "snapshot error summary", "totalErrorRows", totalErrors);
            for (var entry : tableErrorCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    String dbName = tableDbNames.getOrDefault(entry.getKey(), "?");
                    Log.warn(log, "table had errors",
                            "table", dbName + "." + entry.getKey(),
                            "errorRows", entry.getValue());
                }
            }
        }
        totalRows.set(0L);
        tableRowCounts.clear();
        tableErrorCounts.clear();
        tableStartMs.clear();
        tableSampleCount.clear();
        tableLastLogRows.clear();
        tableLastLogMs.clear();
        tableCompleted.clear();
        tableDbNames.clear();
        fieldOrders.clear();
    }

    private void flushBatch(String table, String dbName, List<String> fields,
                            List<Map<String, Object>> rows) {
        String cols = String.join(", ", fields.stream().map(f -> "`" + escapeBacktick(f) + "`").toList());
        String placeholders = String.join(", ", fields.stream().map(f -> "?").toList());
        String sql = "INSERT INTO `" + escapeBacktick(dbName) + "`.`" + escapeBacktick(table)
                + "` (" + cols + ") VALUES (" + placeholders + ")";

        int goodCount = 0;
        long badRows = 0;
        StringBuilder firstError = new StringBuilder();

        try (Connection conn = dataSource.getConnection()) {
            // Disable FK checks so child tables can be inserted before parents.
            // Debezium delivers snapshot events per-table in arbitrary order.
            try (PreparedStatement fkStmt = conn.prepareStatement("SET FOREIGN_KEY_CHECKS = 0")) {
                fkStmt.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                try {
                    converter.bind(ps, dbName, table, row, fields);
                    ps.addBatch();
                    goodCount++;
                } catch (Exception e) {
                    badRows++;
                    if (firstError.isEmpty()) {
                        firstError.append(e.getMessage());
                    }
                }
            }
            if (goodCount > 0) {
                try {
                    ps.executeBatch();
                } catch (SQLException e) {
                    Log.error(log, "batch insert failed",
                            "table", dbName + "." + table,
                            "batchRows", goodCount,
                            "error", e.getMessage());
                    if (firstError.isEmpty()) {
                        firstError.append(e.getMessage());
                    }
                    badRows += goodCount;
                }
            }
            } // close try (PreparedStatement ps)
        } catch (SQLException e) {
            Log.error(log, "flushBatch connection failed",
                    "table", dbName + "." + table,
                    "error", e.getMessage());
            badRows += rows.size();
        }

        if (badRows > 0) {
            Log.warn(log, "rows failed",
                    "table", dbName + "." + table,
                    "failed", badRows,
                    "firstError", firstError.toString());
            tableErrorCounts.merge(table, badRows, Long::sum);
        }
    }

    public void tableComplete(String table, String dbName) {
        // Only fire once per table — snapshot.max.threads splits tables into chunks,
        // and each chunk emits last_in_data_collection, hitting this multiple times.
        if (tableCompleted.putIfAbsent(table, Boolean.TRUE) != null) return;

        // Ensure dbName is tracked (for after=null last events that skip accumulate)
        tableDbNames.putIfAbsent(table, dbName);

        // Flush remaining buffer for this table
        List<Map<String, Object>> rows = buffer.remove(table);
        if (rows != null && !rows.isEmpty()) {
            List<String> cols = new ArrayList<>(fieldOrders.getOrDefault(table, List.of()));
            flushBatch(table, dbName, cols, rows);
        }

        long now = System.currentTimeMillis();
        long rowsCount = tableRowCounts.getOrDefault(table, 0L);
        long errors = tableErrorCounts.getOrDefault(table, 0L);
        long tableMs = now - tableStartMs.getOrDefault(table, startMs);
        double rps = tableMs > 0 ? rowsCount * 1000.0 / tableMs : 0;

        List<Object> fields = new ArrayList<>();
        fields.add("db"); fields.add(dbName);
        fields.add("table"); fields.add(table);
        fields.add("rows"); fields.add(formatRows(rowsCount));
        fields.add("elapsed"); fields.add(formatDuration(tableMs));
        fields.add("speed"); fields.add(formatSpeed(rps));
        if (errors > 0) {
            fields.add("errors"); fields.add(errors);
        }
        Log.info(log, "table complete", fields.toArray());

        // Don't clean up maps here — other chunks of the same table still need
        // tableDbNames, fieldOrders, etc. for async flushes. Cleanup in flushAll().
    }

    public long getTotalRows() { return totalRows.get(); }

    public int getBufferedRows() {
        int n = 0;
        for (var list : buffer.values()) n += list.size();
        return n;
    }

    /** Estimated total pending writes: buffered rows + in-flight futures × batchSize. */
    public long getPendingWrites() {
        long incomplete;
        synchronized (flushFutures) {
            incomplete = flushFutures.stream().filter(f -> !f.isDone()).count();
        }
        return getBufferedRows() + incomplete * batchSize;
    }

    public Map<String, Long> getTableRows() { return new ConcurrentHashMap<>(tableRowCounts); }

    // ── formatting helpers ──────────────────────────────────────────────────

    private static String formatRows(long n) {
        return String.valueOf(n);
    }

    private static String formatDuration(long ms) {
        if (ms < 1_000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long sec = ms / 1000;
        if (sec < 3600) return (sec / 60) + "m" + (sec % 60) + "s";
        long min = sec / 60;
        return (min / 60) + "h" + (min % 60) + "m";
    }

    private static String formatSpeed(double rps) {
        if (rps < 1) return String.format("%.1f/s", rps);
        return (long) rps + "/s";
    }
}
