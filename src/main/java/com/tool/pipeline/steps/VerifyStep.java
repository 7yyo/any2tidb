package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.verifier.VerifyResult;

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
    private final StructuredLogger log;
    private final SummaryPrinter printer;

    public VerifyStep(AppConfig config, SchemaVerifier verifier,
                      StructuredLogger log, SummaryPrinter printer) {
        this.config   = config;
        this.verifier = verifier;
        this.log      = log;
        this.printer  = printer;
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

        List<SummaryPrinter.IssueRow> verifyIssues = new ArrayList<>();

        for (SummaryPrinter.DbSummary db : summaries) {
            // Reconstruct succeededTables as String[][] from convResults (OK/WARN only)
            List<String[]> succeededTables = db.convResults().entrySet().stream()
                    .filter(e -> {
                        var s = e.getValue().getStatus();
                        return s == com.tool.common.model.ConversionResult.Status.OK
                            || s == com.tool.common.model.ConversionResult.Status.WARN;
                    })
                    .map(e -> e.getKey().split("\\.", 2))  // "schema.table" → ["schema","table"]
                    .toList();

            try (Connection ssConn = DriverManager.getConnection(
                         config.getSource().sqlServerJdbcUrlForDB(db.dbName()),
                         config.getSource().getUsername(),
                         config.getSource().getPassword());
                 Connection tidbConn = openTiDB(db.dbName())) {

                List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn, succeededTables);
                verifyIssues.addAll(SummaryPrinter.collectVerifyRows(db.dbName(), results));

            } catch (Exception e) {
                log.log("ERROR", "VERIFY failed", "db", db.dbName(), "error", e.getMessage());
                System.out.println("[ERROR] VERIFY failed for " + db.dbName() + ": " + e.getMessage());
            }
        }

        printer.print(summaries, verifyIssues);
        return StepResult.ok("verify complete");
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrlNoDB(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("USE `" + dbName + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }
}
