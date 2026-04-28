package com.tool.output;

import com.tool.common.model.ConversionResult;
import com.tool.schema.verifier.VerifyResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prints the final VERIFY SUMMARY table to stdout.
 * Receives pre-computed data — has no DB connections.
 */
public class SummaryPrinter {

    public record IssueRow(
            String dbName,
            String tableName,
            String kind,      // "ERROR", "SKIP", "NOTE", "MISMATCH"
            String detail,
            List<String> extra
    ) {
        public IssueRow(String dbName, String tableName, String kind, String detail, List<String> extra) {
            this.dbName    = dbName;
            this.tableName = tableName;
            this.kind      = kind;
            this.detail    = detail;
            this.extra     = extra != null ? extra : List.of();
        }
    }

    /**
     * Collect IssueRows from verify results for one database.
     * Pure transformation — no I/O.
     */
    public static List<IssueRow> collectVerifyRows(
            String dbName,
            List<VerifyResult> results) {

        List<IssueRow> out = new ArrayList<>();
        for (VerifyResult r : results) {
            if (r.isMismatch()) {
                List<String> all = new ArrayList<>(r.diffLines());
                if (r.hasKnownLoss()) all.addAll(r.knownLossLines());
                String first = all.isEmpty() ? "schema mismatch" : all.get(0);
                List<String> rest = all.size() > 1 ? all.subList(1, all.size()) : List.of();
                out.add(new IssueRow(dbName, r.fullTableName(), "MISMATCH", first, rest));
            } else if (r.hasKnownLoss()) {
                List<String> loss = r.knownLossLines();
                if (loss.isEmpty()) continue;
                String first = loss.get(0);
                List<String> rest = loss.size() > 1 ? loss.subList(1, loss.size()) : List.of();
                out.add(new IssueRow(dbName, r.fullTableName(), "NOTE", first, rest));
            }
        }
        return out;
    }

    public record DbSummary(
            String dbName,
            int total,
            int succeededCount,
            Map<String, ConversionResult> convResults
    ) {}

    /** Verify summary goes to any2tidb.log — no longer printed to stdout. */
    public void print(List<DbSummary> summaries, List<IssueRow> verifyIssues) {
        // Output suppressed — see any2tidb.log
    }
}
