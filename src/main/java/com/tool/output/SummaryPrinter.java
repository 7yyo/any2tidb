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
            if (r.isOk()) continue;
            List<VerifyResult.Mismatch> mismatches = r.mismatches();
            String first = mismatches.get(0).reason() + ": " + mismatches.get(0).src() + " → " + mismatches.get(0).tidb();
            List<String> rest = mismatches.size() > 1
                    ? mismatches.subList(1, mismatches.size()).stream()
                        .map(m -> m.reason() + ": " + m.src() + " → " + m.tidb())
                        .toList()
                    : List.of();
            out.add(new IssueRow(dbName, r.fullTableName(), "MISMATCH", first, rest));
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
