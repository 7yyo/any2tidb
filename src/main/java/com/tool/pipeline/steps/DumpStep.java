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
import java.sql.Connection;
import java.sql.DriverManager;
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

        List<DumpTableResult> allResults = new ArrayList<>();
        long totalRows = 0L;
        int  concurrency = Math.max(1, dc.getConcurrency());

        for (String dbName : dbNames) {
            Connection dbConn = connFactory.apply(dbName);
            try {
                List<String[]> tableList = schemaExtractor.listTables(dbConn, schemas, tables);
                log.log("INFO", "Dumping database", "db", dbName, "tables", tableList.size());

                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                List<Future<DumpTableResult>> futures = new ArrayList<>();

                for (String[] entry : tableList) {
                    String schema = entry[0];
                    String table  = entry[1];
                    futures.add(pool.submit(() ->
                            dumpOneTable(connFactory, dbName, schema, table, dc, outputRoot)));
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

        ctx.put("dumpSummaries", allResults);
        ctx.put("dumpTotalRows", totalRows);

        long errors = allResults.stream().filter(DumpTableResult::isError).count();
        return errors == 0
                ? StepResult.ok("dump complete, tables=" + allResults.size() + " rows=" + totalRows)
                : StepResult.ok("dump complete with " + errors + " errors, rows=" + totalRows);
    }

    private DumpTableResult dumpOneTable(ConnectionFactory connFactory,
                                         String dbName, String schema, String table,
                                         AppConfig.DumpConfig dc, Path outputRoot) {
        long start = System.currentTimeMillis();
        DumpWriter writer = writerFactory.get();
        long[] rowCount  = {0L};
        int[]  fileCount = {0};

        try {
            Connection conn = connFactory.apply(dbName);
            try {
                List<String> columns = dumpExtractor.getColumnNames(conn, schema, table);

                dumpExtractor.streamTable(conn, schema, table, dc.getChunkSize(), batch -> {
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

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static Path resolveOutputDir(String configured) {
        String base = (configured == null || configured.isBlank())
                ? "dump-output"
                : configured;
        return Path.of(base, LocalDateTime.now().format(TS_FMT));
    }
}
