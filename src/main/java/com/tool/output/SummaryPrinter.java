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

    /** Print the unified VERIFY SUMMARY table to stdout. */
    public void print(List<DbSummary> summaries, List<IssueRow> verifyIssues) {
        List<IssueRow> allIssues = new ArrayList<>();
        for (DbSummary db : summaries) {
            for (Map.Entry<String, ConversionResult> e : db.convResults().entrySet()) {
                ConversionResult cr = e.getValue();
                if (cr.getStatus() == ConversionResult.Status.ERROR) {
                    allIssues.add(new IssueRow(db.dbName(), e.getKey(), "ERROR",
                            cr.getErrorMessage(), List.of()));
                } else if (cr.getStatus() == ConversionResult.Status.SKIP) {
                    allIssues.add(new IssueRow(db.dbName(), e.getKey(), "SKIP",
                            cr.getErrorMessage(), List.of()));
                }
            }
        }
        allIssues.addAll(verifyIssues);

        int totalTables   = summaries.stream().mapToInt(DbSummary::total).sum();
        int totalOk       = summaries.stream().mapToInt(DbSummary::succeededCount).sum();
        int totalErr      = count(allIssues, "ERROR");
        int totalSkip     = count(allIssues, "SKIP");
        int totalMismatch = count(allIssues, "MISMATCH");
        int totalNote     = count(allIssues, "NOTE");

        int kindW  = 5;
        int dbW    = Math.max(2, allIssues.stream().mapToInt(i -> i.dbName().length()).max().orElse(2));
        int tableW = Math.max(5, allIssues.stream().mapToInt(i -> i.tableName().length()).max().orElse(5));
        int sepLen = 1 + kindW + 2 + dbW + 2 + tableW + 2 + 60;
        String sep    = " " + "─".repeat(sepLen);
        String rowFmt = " %-" + kindW + "s  %-" + dbW + "s  %-" + tableW + "s  %s%n";

        System.out.println(sep);
        System.out.println();
        System.out.println(" VERIFY SUMMARY");
        System.out.println(sep);

        StringBuilder globalLine = new StringBuilder(" ");
        globalLine.append(summaries.size()).append(" databases  ")
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

        System.out.println();
        System.out.printf(rowFmt, "KIND", "DB", "TABLE", "DETAIL");
        System.out.println(sep);

        boolean firstGroup = true;
        for (String kind : List.of("NOTE", "MISMATCH", "SKIP", "ERROR")) {
            List<IssueRow> group = allIssues.stream()
                    .filter(r -> kind.equals(r.kind())).toList();
            if (group.isEmpty()) continue;
            if (!firstGroup) System.out.println(sep);
            firstGroup = false;
            for (IssueRow r : group) {
                System.out.printf(rowFmt, r.kind(), r.dbName(), r.tableName(), r.detail());
                for (String line : r.extra()) System.out.printf(rowFmt, "", "", "", line);
            }
        }
        System.out.println(sep);
        System.out.println();
    }

    private static int count(List<IssueRow> rows, String kind) {
        return (int) rows.stream().filter(r -> kind.equals(r.kind())).count();
    }
}
