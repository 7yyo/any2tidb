package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.output.SummaryPrinter;
import com.tool.common.model.ConversionResult;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.verifier.VerifyResult;
import com.tool.source.SourceDriver;

import static com.tool.common.SqlUtils.escapeBacktick;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

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
        int totalNote = 0;
        int totalDbFailed = 0;

        for (SummaryPrinter.DbSummary db : summaries) {
            // Reconstruct succeededTables as String[][] from convResults (OK/WARN only)
            List<String[]> succeededTables = db.convResults().entrySet().stream()
                    .filter(e -> {
                        var s = e.getValue().getStatus();
                        return s == ConversionResult.Status.OK
                            || s == ConversionResult.Status.WARN;
                    })
                    .map(e -> e.getKey().split("\\.", 2))  // "schema.table" → ["schema","table"]
                    .toList();

            if (succeededTables.isEmpty()) continue;

            int dbMismatch = 0;
            int dbNote = 0;

            Log.info(log, "VERIFY " + db.dbName(), "tables", succeededTables.size());

            try (Connection ssConn = DriverManager.getConnection(
                         sourceDriver.buildJdbcUrlTo(config.getSource(), db.dbName()),
                         config.getSource().getUsername(),
                         config.getSource().getPassword());
                 Connection tidbConn = openTiDB(db.dbName())) {

                List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn, succeededTables);

                for (VerifyResult r : results) {
                    if (r.isMismatch()) {
                        dbMismatch++;
                        List<String> diffs = r.diffLines();
                        Log.warn(log, r.fullTableName(), "status", "MISMATCH (" + diffs.size() + ")");
                        for (int j = 0; j < diffs.size(); j++) {
                            String prefix = (j == diffs.size() - 1) ? "  └─ " : "  ├─ ";
                            Log.warn(log, prefix + diffs.get(j));
                        }
                    }
                    if (r.hasKnownLoss()) {
                        dbNote++;
                        List<String> notes = r.knownLossLines();
                        Log.info(log, r.fullTableName(), "status", "NOTE (" + notes.size() + ")");
                        for (int j = 0; j < notes.size(); j++) {
                            String prefix = (j == notes.size() - 1) ? "  └─ " : "  ├─ ";
                            Log.info(log, prefix + notes.get(j));
                        }
                    }
                }

            } catch (Exception e) {
                Log.error(log, "VERIFY failed", "db", db.dbName(), "error", e.getMessage());
                totalDbFailed++;
                continue;
            }

            Log.info(log, "VERIFY " + db.dbName(), "mismatch", dbMismatch, "note", dbNote);
            totalMismatch += dbMismatch;
            totalNote += dbNote;
        }

        Log.info(log, "VERIFY complete", "mismatch", totalMismatch, "note", totalNote,
                "dbFailed", totalDbFailed);
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
