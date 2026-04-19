package com.tool;

import com.tool.common.model.ConversionResult;
import com.tool.config.AppConfig;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.common.model.TableSchema;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.verifier.VerifyResult;
import com.tool.schema.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SqlServerExtractor extractor;
    private final SchemaConverter converter;
    private final TiDBWriter writer;
    private final SchemaVerifier verifier;

    /** File logger — all verbose progress output goes here, not to stdout. */
    private PrintWriter fileLog;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS Z");

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter,
               TiDBWriter writer, SchemaVerifier verifier) {
        this.config = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer = writer;
        this.verifier = verifier;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Open file logger (append mode so repeated runs accumulate)
        try {
            fileLog = new PrintWriter(new FileWriter("ms2tidb.log", true), true);
        } catch (Exception e) {
            // Fall back to /dev/null — file logging is best-effort
            fileLog = new PrintWriter(new java.io.OutputStream() { public void write(int b) {} });
        }
        flog("INFO", "ms2tidb started");

        boolean dryRun = args.containsOption("dry-run");

        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(",")).map(String::trim).collect(Collectors.toList());
        }

        List<String> schemas = config.getConvert().getSchemas();
        List<String> tables = tablesOverride != null ? tablesOverride : config.getConvert().getTables();
        boolean dropIfExists = config.getConvert().isDropIfExists();
        boolean continueOnError = config.getConvert().isContinueOnError();

        // ── Startup banner ────────────────────────────────────────────────────
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│  ms2tidb  SQL Server → TiDB Migration   │");
        System.out.println("├──────────┬──────────────────────────────┤");
        System.out.printf( "│  source  │  %-28s│%n",
                config.getSource().getHost() + ":" + config.getSource().getPort());
        System.out.printf( "│  target  │  %-28s│%n",
                config.getTarget().getHost() + ":" + config.getTarget().getPort());
        if (!schemas.isEmpty())
            System.out.printf("│  schemas │  %-28s│%n", schemas.toString());
        if (!tables.isEmpty())
            System.out.printf("│  tables  │  %-28s│%n", tables.toString());
        if (dryRun)
            System.out.println("│  mode    │  dry-run                     │");
        System.out.println("└──────────┴──────────────────────────────┘");
        System.out.println();

        int totalFailed = 0;
        List<DbVerifyPending> verifyPending = new ArrayList<>();

        flog("INFO", "Connecting to SQL Server...");
        List<String> dbNames;
        try (Connection masterConn = DriverManager.getConnection(
                config.getSource().sqlServerJdbcUrlNoDB(),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = extractor.listDatabases(masterConn);
        }
        flog("INFO", "Found databases", "count", dbNames.size(), "databases", dbNames.toString());
        dbNameWidth = dbNames.stream().mapToInt(String::length).max().orElse(8);

        for (String dbName : dbNames) {
            flog("INFO", "Processing database", "db", dbName);
            try (Connection ssConn = DriverManager.getConnection(
                    config.getSource().sqlServerJdbcUrlForDB(dbName),
                    config.getSource().getUsername(),
                    config.getSource().getPassword());
                 Connection tidbConn = dryRun ? null : openTiDB(dbName)) {
                MigrateResult mr = migrateOneDatabase(ssConn, tidbConn, dbName,
                        schemas, tables, dropIfExists, continueOnError, dryRun);
                totalFailed += mr.failed;
                if (!dryRun) {
                    verifyPending.add(new DbVerifyPending(dbName, mr.allTables.size(),
                            mr.warned, mr.skipped, mr.allTables, mr.succeededTables, mr.convResults));
                }
                if (mr.stoppedEarly) {
                    flog("WARN", "Stopped early", "reason", "continueOnError=false");
                    clearProgress();
                    System.out.println("[WARN ] Stopped early (continueOnError=false) — see ms2tidb.log");
                    break;
                }
            }
        }

        // Clear the progress line before printing verify results
        clearProgress();

        // ── Collect + print all VERIFY results in one unified table ──────────
        if (!verifyPending.isEmpty()) {
            List<IssueRow> issues = new ArrayList<>();
            for (DbVerifyPending vp : verifyPending) {
                try (Connection ssConn = DriverManager.getConnection(
                        config.getSource().sqlServerJdbcUrlForDB(vp.dbName),
                        config.getSource().getUsername(),
                        config.getSource().getPassword());
                     Connection tidbConn = openTiDB(vp.dbName)) {
                    collectVerifyRows(tidbConn, ssConn, vp, issues);
                } catch (Exception e) {
                    System.out.println("[ERROR] VERIFY failed for " + vp.dbName + ": " + e.getMessage());
                    flog("ERROR", "VERIFY failed", "db", vp.dbName, "error", e.getMessage());
                }
            }
            printSummary(verifyPending, issues);
        }

        flog("INFO", "ms2tidb finished");
        fileLog.close();

        if (totalFailed > 0) System.exit(1);
    }

    // ── Lightweight result carriers ───────────────────────────────────────────

    private static class MigrateResult {
        final int failed;
        final int warned;
        final int skipped;
        final boolean stoppedEarly;
        final List<String[]> allTables;
        final List<String[]> succeededTables;
        final Map<String, ConversionResult> convResults;
        MigrateResult(int failed, int warned, int skipped, boolean stoppedEarly,
                       List<String[]> allTables, List<String[]> succeededTables,
                       Map<String, ConversionResult> convResults) {
            this.failed = failed;
            this.warned = warned;
            this.skipped = skipped;
            this.stoppedEarly = stoppedEarly;
            this.allTables = allTables;
            this.succeededTables = succeededTables;
            this.convResults = convResults;
        }
    }

    private static class DbVerifyPending {
        final String dbName;
        final int total;
        final int warned;
        final int skipped;
        final List<String[]> allTables;
        final List<String[]> succeededTables;
        final Map<String, ConversionResult> convResults;
        DbVerifyPending(String dbName, int total, int warned, int skipped,
                         List<String[]> allTables, List<String[]> succeededTables,
                         Map<String, ConversionResult> convResults) {
            this.dbName = dbName;
            this.total = total;
            this.warned = warned;
            this.skipped = skipped;
            this.allTables = allTables;
            this.succeededTables = succeededTables;
            this.convResults = convResults;
        }
    }

    private MigrateResult migrateOneDatabase(Connection ssConn, Connection tidbConn,
                                             String dbName,
                                             List<String> schemas, List<String> tables,
                                             boolean dropIfExists, boolean continueOnError,
                                             boolean dryRun) throws Exception {
        List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
        flog("INFO", "Starting conversion", "db", dbName, "tables", tableList.size());

        // Pre-check: detect table name conflicts across schemas
        Map<String, List<String>> nameToSchemas = new LinkedHashMap<>();
        for (String[] entry : tableList) {
            nameToSchemas.computeIfAbsent(entry[1], k -> new ArrayList<>()).add(entry[0]);
        }
        List<String> conflicts = nameToSchemas.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "  \"" + e.getKey() + "\"  →  " + String.join(", ",
                        e.getValue().stream().map(s -> s + "." + e.getKey()).toList()))
                .toList();
        if (!conflicts.isEmpty()) {
            clearProgress();
            System.out.println("[ERROR] Table name conflict in [" + dbName + "] — see ms2tidb.log");
            flog("ERROR", "Table name conflict", "db", dbName);
            conflicts.forEach(line -> flog("ERROR", "conflict", "tables", line));
            flog("ERROR", "Hint: set convert.schemas=<schema> in application.yml to migrate one schema at a time");
            return new MigrateResult(1, 0, 0, true, tableList, List.of(), Map.of());
        }

        int total = tableList.size();
        int succeeded = 0, warned = 0, failed = 0, skipped = 0;
        List<String[]> succeededTables = new ArrayList<>();
        Map<String, ConversionResult> convResults = new LinkedHashMap<>();
        boolean stopEarly = false;

        for (int i = 0; i < total; i++) {
            String[] entry = tableList.get(i);
            String schemaName = entry[0], tableName = entry[1];
            String fullName = schemaName + "." + tableName;

            // Render progress bar on a single overwritten line
            printProgress(dbName, i, total, skipped, fullName);

            ConversionResult result = new ConversionResult(fullName);
            try {
                TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);

                if (ddl == null) {
                    // result already has ERROR set by converter (unskippable columns)
                } else if (dryRun) {
                    writer.printDDL(fullName, ddl);
                } else {
                    writer.executeDDL(tidbConn, ddl, result);
                }
            } catch (Exception e) {
                result.setError(e.getMessage());
            }

            switch (result.getStatus()) {
                case OK -> {
                    flog("INFO", "table converted", "db", dbName, "table", fullName, "status", "OK",
                            "progress", (i+1) + "/" + total);
                    succeeded++;
                    succeededTables.add(entry);
                    convResults.put(fullName, result);
                }
                case WARN -> {
                    flog("INFO", "table converted", "db", dbName, "table", fullName, "status", "WARN",
                            "progress", (i+1) + "/" + total, "warnings", result.getWarnings().size());
                    for (String w : result.getWarnings()) flog("WARN", "warning", "table", fullName, "detail", w);
                    succeeded++;
                    warned++;
                    succeededTables.add(entry);
                    convResults.put(fullName, result);
                }
                case ERROR -> {
                    flog("ERROR", "table failed", "db", dbName, "table", fullName,
                            "progress", (i+1) + "/" + total, "error", result.getErrorMessage());
                    failed++;
                    convResults.put(fullName, result);
                    if (!continueOnError) { stopEarly = true; }
                }
                case SKIP -> {
                    flog("WARN", "table skipped", "db", dbName, "table", fullName,
                            "progress", (i+1) + "/" + total, "reason", result.getErrorMessage());
                    skipped++;
                    convResults.put(fullName, result);
                }
            }
            if (stopEarly) break;
        }

        // Final progress line — show completion
        printProgress(dbName, total, total, skipped, "");
        System.out.println(); // newline after progress bar
        flog("INFO", "Conversion completed", "db", dbName, "succeeded", succeeded, "warned", warned, "failed", failed, "skipped", skipped);

        return new MigrateResult(failed, warned, skipped, stopEarly, tableList, succeededTables, convResults);
    }

    private Connection openTiDB(String dbName) throws Exception {
        flog("INFO", "Connecting to TiDB", "db", dbName);
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrlNoDB(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "`");
            st.execute("USE `" + dbName + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }

    // -------------------------------------------------------------------------
    // VERIFY output
    // -------------------------------------------------------------------------

    // ── Issue row (ERROR / SKIP / MISMATCH only) ───────────────────────────────

    private static class IssueRow {
        final String dbName;
        final String tableName;
        final String kind;        // "ERROR", "SKIP", "NOTE", "MISMATCH"
        final String detail;      // single-line summary or first diff line
        final List<String> extra; // additional diff lines

        IssueRow(String dbName, String tableName, String kind, String detail, List<String> extra) {
            this.dbName = dbName; this.tableName = tableName; this.kind = kind;
            this.detail = detail; this.extra = extra != null ? extra : List.of();
        }
    }

    // ── Collect verify rows for one database ──────────────────────────────────

    private void collectVerifyRows(Connection tidbConn, Connection msConn,
                                   DbVerifyPending vp, List<IssueRow> out) {
        List<VerifyResult> results;
        try {
            results = verifier.verifyAll(msConn, tidbConn, vp.succeededTables);
        } catch (Exception e) {
            flog("ERROR", "VERIFY failed", "db", vp.dbName, "error", e.getMessage());
            System.out.println("[ERROR] VERIFY failed for " + vp.dbName + ": " + e.getMessage());
            return;
        }

        for (VerifyResult r : results) {
            if (r.isMismatch()) {
                List<String> diff = r.diffLines();
                // If there's also known loss, append loss lines after the mismatch lines
                List<String> allLines = new java.util.ArrayList<>(diff);
                if (r.hasKnownLoss()) allLines.addAll(r.knownLossLines());
                String first = allLines.isEmpty() ? "schema mismatch" : allLines.get(0);
                List<String> rest = allLines.size() > 1 ? allLines.subList(1, allLines.size()) : List.of();
                out.add(new IssueRow(vp.dbName, r.fullTableName(), "MISMATCH", first, rest));
            } else if (r.hasKnownLoss()) {
                List<String> lossLines = r.knownLossLines();
                String first = lossLines.get(0);
                List<String> rest = lossLines.size() > 1 ? lossLines.subList(1, lossLines.size()) : List.of();
                out.add(new IssueRow(vp.dbName, r.fullTableName(), "NOTE", first, rest));
            }
        }
    }

    // ── Print final summary ────────────────────────────────────────────────────

    private void printSummary(List<DbVerifyPending> pending, List<IssueRow> issues) {
        // ── Collect ERROR and SKIP issues ─────────────────────────────────────
        List<IssueRow> allIssues = new ArrayList<>();
        for (DbVerifyPending vp : pending) {
            for (Map.Entry<String, ConversionResult> e : vp.convResults.entrySet()) {
                ConversionResult cr = e.getValue();
                if (cr.getStatus() == ConversionResult.Status.ERROR) {
                    allIssues.add(new IssueRow(vp.dbName, e.getKey(), "ERROR",
                            cr.getErrorMessage(), List.of()));
                } else if (cr.getStatus() == ConversionResult.Status.SKIP) {
                    allIssues.add(new IssueRow(vp.dbName, e.getKey(), "SKIP",
                            cr.getErrorMessage(), List.of()));
                }
            }
        }
        allIssues.addAll(issues); // NOTE and MISMATCH rows

        // ── Global totals ──────────────────────────────────────────────────────
        int totalTables  = pending.stream().mapToInt(vp -> vp.total).sum();
        int totalOk      = pending.stream().mapToInt(vp -> vp.succeededTables.size()).sum();
        int totalErr     = (int) allIssues.stream().filter(i -> "ERROR".equals(i.kind)).count();
        int totalSkip    = (int) allIssues.stream().filter(i -> "SKIP".equals(i.kind)).count();
        int totalMismatch= (int) allIssues.stream().filter(i -> "MISMATCH".equals(i.kind)).count();
        int totalNote    = (int) allIssues.stream().filter(i -> "NOTE".equals(i.kind)).count();

        // ── Column widths for issue table ──────────────────────────────────────
        int kindW  = 5; // "ERROR"
        int dbW    = Math.max(2, allIssues.stream().mapToInt(i -> i.dbName.length()).max().orElse(2));
        int tableW = Math.max(5, allIssues.stream().mapToInt(i -> i.tableName.length()).max().orElse(5));
        int sepLen = 1 + kindW + 2 + dbW + 2 + tableW + 2 + 60;
        String sep     = " " + "─".repeat(sepLen);
        String hdrFmt  = " %-" + kindW + "s  %-" + dbW + "s  %-" + tableW + "s  %s%n";
        String rowFmt  = hdrFmt;

        // ── Separator after progress bars ──────────────────────────────────────
        System.out.println(sep);
        System.out.println();

        // ── VERIFY SUMMARY header + totals ─────────────────────────────────────
        System.out.println(" VERIFY SUMMARY");
        System.out.println(sep);
        StringBuilder globalLine = new StringBuilder(" ");
        globalLine.append(pending.size()).append(" databases  ")
                  .append(totalTables).append(" tables  ")
                  .append("OK: ").append(totalOk);
        if (totalErr      > 0) globalLine.append("  ERR: ").append(totalErr);
        if (totalSkip     > 0) globalLine.append("  SKIP: ").append(totalSkip);
        if (totalMismatch > 0) globalLine.append("  MISMATCH: ").append(totalMismatch);
        if (totalNote     > 0) globalLine.append("  NOTE: ").append(totalNote);
        System.out.println(globalLine);
        System.out.println(sep);

        if (allIssues.isEmpty()) {
            System.out.println();
            return;
        }

        // ── Issue table ────────────────────────────────────────────────────────
        System.out.println();
        System.out.printf(hdrFmt, "KIND", "DB", "TABLE", "DETAIL");
        System.out.println(sep);

        List<String> kindOrder = List.of("NOTE", "MISMATCH", "SKIP", "ERROR");
        boolean firstGroup = true;
        for (String kind : kindOrder) {
            List<IssueRow> group = allIssues.stream()
                    .filter(r -> kind.equals(r.kind)).collect(Collectors.toList());
            if (group.isEmpty()) continue;
            if (!firstGroup) System.out.println(sep);
            firstGroup = false;
            for (IssueRow r : group) {
                System.out.printf(rowFmt, r.kind, r.dbName, r.tableName, r.detail);
                for (String line : r.extra) {
                    System.out.printf(rowFmt, "", "", "", line);
                }
            }
        }
        System.out.println(sep);
        System.out.println();
    }

    // ── Logging & progress helpers ────────────────────────────────────────────

    /** Write a structured log line to the log file only (not stdout).
     *  Format: [timestamp] [LEVEL] ["message"] [key=val] ...
     *  fields must be alternating key, value pairs. */
    private void flog(String level, String message, Object... fields) {
        if (fileLog == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ZonedDateTime.now().format(TS)).append(']');
        sb.append(" [").append(level).append(']');
        sb.append(" [\"").append(message).append("\"]");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String k = String.valueOf(fields[i]);
            String v = String.valueOf(fields[i + 1]);
            boolean quote = v.contains(" ") || v.isEmpty();
            sb.append(" [").append(k).append('=');
            if (quote) sb.append('"').append(v).append('"');
            else       sb.append(v);
            sb.append(']');
        }
        fileLog.println(sb);
    }

    /** Last progress line length — used to pad/erase on overwrite. */
    private int lastProgressLen = 0;
    /** Fixed width for the db name column in the progress bar. */
    private int dbNameWidth = 0;

    /**
     * Render an in-place progress bar on stdout.
     * Uses \r to overwrite the current line — no newline is emitted.
     *
     * @param dbName   database being converted
     * @param done     tables completed so far (0-based: pass i before processing table i)
     * @param total    total tables
     * @param current  name of the table currently being processed
     */
    private void printProgress(String dbName, int done, int total, int skipCount, String current) {
        int barWidth = 20;
        int filled = (total == 0) ? barWidth : (int) Math.round((double) done / total * barWidth);
        String bar = "━".repeat(filled) + "─".repeat(barWidth - filled);
        String line = String.format(" %-" + dbNameWidth + "s  [%s]  %d/%d  %s", dbName, bar, done, total, current);
        // Pad to erase previous longer line
        if (line.length() < lastProgressLen) {
            line = line + " ".repeat(lastProgressLen - line.length());
        }
        lastProgressLen = line.length();
        System.out.print("\r" + line);
        System.out.flush();
    }

    /** Erase the progress line (call before printing multi-line output). */
    private void clearProgress() {
        if (lastProgressLen > 0) {
            System.out.print("\r" + " ".repeat(lastProgressLen) + "\r");
            System.out.flush();
            lastProgressLen = 0;
        }
    }
}
