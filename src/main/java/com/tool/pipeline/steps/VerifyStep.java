package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import com.tool.source.SourceDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.output.SummaryPrinter;
import com.tool.common.model.ConversionResult;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.verifier.VerifyResult;

import static com.tool.common.SqlUtils.escapeBacktick;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Re-connects to both databases and runs schema verification,
 * then prints the unified VERIFY SUMMARY table.
 *
 * Reads from context:
 *   "dbSummaries" (List<SummaryPrinter.DbSummary>)
 *   "dryRun"      (Boolean) — skips verify entirely on dry-run
 */
public class VerifyStep implements MigrationStep {

    private final AppConfig config;
    private final SchemaVerifier verifier;
    private final SourceDriver sourceDriver;
    private static final Logger log = LoggerFactory.getLogger(VerifyStep.class);

    public VerifyStep(AppConfig config, SchemaVerifier verifier, SourceDriver sourceDriver) {
        this.config   = config;
        this.verifier = verifier;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "Verify"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        boolean dryRun = Boolean.TRUE.equals(ctx.get("dryRun", Boolean.class));
        if (dryRun) return StepResult.ok("skipped in dry-run mode");

        List<SummaryPrinter.DbSummary> summaries = ctx.get("dbSummaries", List.class);
        if (summaries == null || summaries.isEmpty()) {
            return StepResult.ok("no databases to verify");
        }

        int totalMismatch = 0;
        int totalDbFailed = 0;

        for (SummaryPrinter.DbSummary db : summaries) {
            List<String[]> succeededTables = db.convResults().entrySet().stream()
                    .filter(e -> {
                        var s = e.getValue().getStatus();
                        return s == ConversionResult.Status.OK
                            || s == ConversionResult.Status.WARN;
                    })
                    .map(e -> e.getKey().split("\\.", 2))
                    .toList();

            if (succeededTables.isEmpty()) continue;

            int dbMismatch = 0;
            int dbOk = 0;

            Log.info(log, "VERIFY starting", "db", db.dbName(), "tables", succeededTables.size());

            try (Connection ssConn = DriverManager.getConnection(
                         sourceDriver.buildJdbcUrlTo(config.getSource(), db.dbName()),
                         config.getSource().getUsername(),
                         config.getSource().getPassword());
                 Connection tidbConn = openTiDB(db.dbName())) {

                // ── Check: tables (migration scope vs TiDB) ──
                // Compare ALL tables the schema migration attempted (not just OK/WARN).
                // ERROR/SKIP tables also won't exist on TiDB — report them as missing.
                Set<String> expectedTables = db.convResults().keySet().stream()
                        .map(k -> k.split("\\.", 2)[1].toLowerCase())
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
                Set<String> tidbTables = new java.util.LinkedHashSet<>();
                try (var rs = tidbConn.getMetaData().getTables(tidbConn.getCatalog(), null, null, new String[]{"TABLE"})) {
                    while (rs.next()) tidbTables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
                if (!expectedTables.equals(tidbTables)) {
                    Set<String> missing = new java.util.LinkedHashSet<>(expectedTables);
                    missing.removeAll(tidbTables);
                    Set<String> extra = new java.util.LinkedHashSet<>(tidbTables);
                    extra.removeAll(expectedTables);
                    if (!missing.isEmpty() || !extra.isEmpty()) {
                        dbMismatch++;
                        Log.warn(log, "VERIFY tables", "db", db.dbName(), "reason", "tables",
                                "missing", missing.isEmpty() ? "[]" : "[" + String.join(", ", missing) + "]",
                                "extra", extra.isEmpty() ? "[]" : "[" + String.join(", ", extra) + "]");
                    }
                }

                // ── Per-table verify ──
                List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn, succeededTables);

                for (int i = 0; i < results.size(); i++) {
                    VerifyResult r = results.get(i);
                    String prefix = (i == results.size() - 1) ? "  └─ " : "  ├─ ";

                    if (r.isOk()) {
                        dbOk++;
                    } else {
                        dbMismatch++;
                        List<VerifyResult.Mismatch> mismatches = r.mismatches();
                        Log.warn(log, prefix + r.fullTableName(),
                                "status", "MISMATCH",
                                "reason", mismatches.get(0).reason(),
                                "src", mismatches.get(0).src(),
                                "tidb", mismatches.get(0).tidb());
                        for (int j = 1; j < mismatches.size(); j++) {
                            VerifyResult.Mismatch m = mismatches.get(j);
                            String subPrefix = (i == results.size() - 1) ? "     " : "  │  ";
                            Log.warn(log, subPrefix + m.reason(),
                                    "src", m.src(), "tidb", m.tidb());
                        }
                    }
                }

            } catch (Exception e) {
                Log.error(log, "VERIFY failed", "db", db.dbName(), "error", e.getMessage());
                totalDbFailed++;
                continue;
            }

            Log.info(log, "VERIFY complete for database", "db", db.dbName(),
                    "ok", dbOk, "mismatch", dbMismatch);
            totalMismatch += dbMismatch;
        }

        Log.info(log, "VERIFY complete", "mismatch", totalMismatch, "dbFailed", totalDbFailed);
        return StepResult.ok("verify complete");
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrl(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("USE `" + escapeBacktick(dbName) + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }

}
