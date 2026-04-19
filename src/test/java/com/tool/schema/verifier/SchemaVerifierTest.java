package com.tool.schema.verifier;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVerifierTest {

    /** 构造一个完全匹配的 VerifyResult（无 dropped 索引） */
    private VerifyResult ok() {
        List<String> cols = List.of("id", "name", "email", "age", "flag");
        return new VerifyResult(
                "dbo.users",
                5, 5,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of("idx_name", "idx_email"), Set.of(), Set.of("idx_name", "idx_email"),
                0, 0,
                3, 3,
                "id", "id",
                Map.of(), Map.of(), Map.of(), Map.of()
        );
    }

    @Test
    void okResult_isNotMismatch() {
        assertFalse(ok().isMismatch());
    }

    @Test
    void okResult_diffLinesIsEmpty() {
        assertTrue(ok().diffLines().isEmpty());
    }

    @Test
    void okResult_hasNoKnownLoss() {
        assertFalse(ok().hasKnownLoss());
        assertTrue(ok().knownLossLines().isEmpty());
    }

    @Test
    void colCountDiff_isMismatch() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 5, 4,
                List.of("id","a","b","c","d"), List.of("id","a","b","c"),
                List.of("id"), List.of("id"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 3, 3, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols")));
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("d")));
    }

    @Test
    void extraColInTidb_showsInDiffLines() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 4,
                List.of("id","a","b"), List.of("id","a","b","extra"),
                List.of("id"), List.of("id"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("extra cols")));
    }

    @Test
    void colOrderMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                List.of("id","a","b"), List.of("id","b","a"),
                List.of("id"), List.of("id"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("col order mismatch")));
    }

    @Test
    void pkMismatch_isMismatchAndReported() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a","b"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("pk mismatch")));
    }

    @Test
    void idxMismatch_isMismatchAndReported() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("idx_x", "idx_y", "idx_z"), Set.of(), Set.of("idx_x", "idx_y"),
                0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx missing in TiDB")));
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx_z")));
    }

    @Test
    void droppedIdx_isNotMismatch_butIsKnownLoss() {
        // COLUMNSTORE index was dropped during migration — not in msIdxNames, only in msDroppedIdxNames
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("idx_regular"), Set.of("IX_cs_edge"), Set.of("idx_regular"),
                0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertFalse(r.isMismatch(), "dropped index should not cause MISMATCH");
        assertTrue(r.hasKnownLoss());
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("IX_cs_edge")));
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("idx dropped")));
    }

    @Test
    void checkDiff_isNotMismatch() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 2, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void notNullMismatch_isMismatchAndReported() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 3, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("notnull mismatch")));
    }

    @Test
    void aiMismatch_isMismatchAndReported() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, "id", null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("ai mismatch")));
    }

    @Test
    void defaultMismatch_isMismatchAndReported() {
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, null, null,
                Map.of("b", "0"),
                Map.of("b", "1"),
                Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("default[b]")));
    }

    @Test
    void emptyStringDefault_isNotMismatch() {
        // MS default=('') → normalizeDefault strips parens → '' → strips quotes → ""
        // TiDB JDBC returns "" (empty string, length 0) for DEFAULT ''
        List<String> cols = List.of("a", "note");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of(), Set.of(), Set.of(), 0, 0, 1, 1, null, null,
                Map.of("note", "''"),    // after paren-stripping: '' (raw from normalizeDefault)
                Map.of("note", ""),      // TiDB JDBC returns empty string for DEFAULT ''
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "Empty-string default must not be a MISMATCH, diffLines=" + r.diffLines());
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void literalStringDefault_stripsQuotes_isNotMismatch() {
        // MS default=('CN') → '' → 'CN' → normalizeDefault strips quotes → CN
        // TiDB JDBC COLUMN_DEF returns CN (no quotes)
        List<String> cols = List.of("a", "country");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of(), Set.of(), Set.of(), 0, 0, 1, 1, null, null,
                Map.of("country", "'CN'"),
                Map.of("country", "CN"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "String literal default quote-stripping must not cause MISMATCH");
        assertTrue(r.diffLines().isEmpty());
    }



    @Test
    void defaultCaseInsensitive_isNotMismatch() {
        List<String> cols = List.of("a", "created_at", "c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of("i1"), Set.of(), Set.of("i1"), 0, 0, 2, 2, null, null,
                Map.of("created_at", "CURRENT_TIMESTAMP"),
                Map.of("created_at", "current_timestamp"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "Case-only difference in defaults should not be a mismatch");
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void nullVsEmpty_defaultsAreEqual() {
        List<String> cols = List.of("a", "b");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("a"), List.of("a"),
                Set.of(), Set.of(), Set.of(), 0, 0, 1, 1, null, null,
                Map.of(),
                Map.of(),
                Map.of(), Map.of());
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void currentTimestampWithPrecision_isNotMismatch() {
        List<String> cols = List.of("id", "created_at");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("created_at", "CURRENT_TIMESTAMP"),
                Map.of("created_at", "CURRENT_TIMESTAMP(3)"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "CURRENT_TIMESTAMP vs CURRENT_TIMESTAMP(3) should not be a mismatch, diffLines=" + r.diffLines());
        assertTrue(r.diffLines().isEmpty());
    }

    // ── Known-loss default tests ──────────────────────────────────────────────

    @Test
    void uuidDropped_isNotMismatch_butIsKnownLoss() {
        List<String> cols = List.of("id", "uid");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("uid", "UUID()"),
                Map.of(),          // TiDB has no default for uid (dropped)
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "UUID() drop should not be MISMATCH, diffLines=" + r.diffLines());
        assertTrue(r.hasKnownLoss());
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("default[uid]")));
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("UUID()")));
    }

    @Test
    void utcTimestampReplaced_isNotMismatch_notLoss() {
        List<String> cols = List.of("id", "ts");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("ts", "UTC_TIMESTAMP()"),
                Map.of("ts", "CURRENT_TIMESTAMP(6)"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "UTC_TIMESTAMP→CURRENT_TIMESTAMP should not be MISMATCH");
        assertFalse(r.hasKnownLoss(), "UTC_TIMESTAMP→CURRENT_TIMESTAMP is a silent conversion, not LOSS");
        assertTrue(r.knownLossLines().isEmpty());
    }

    @Test
    void currentTimestampToCurrentDate_isNotMismatch_notLoss() {
        // DATE column: CURDATE() shows as CURRENT_DATE in TiDB
        List<String> cols = List.of("id", "today");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("today", "CURRENT_TIMESTAMP"),
                Map.of("today", "CURRENT_DATE"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "CURRENT_TIMESTAMP→CURRENT_DATE should not be MISMATCH");
        assertFalse(r.hasKnownLoss(), "CURRENT_TIMESTAMP→CURRENT_DATE is a silent conversion, not LOSS");
        assertTrue(r.knownLossLines().isEmpty());
    }

    @Test
    void multipleMismatches_allReportedInDiffLines() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 4, 3,
                List.of("id","a","b","c"), List.of("id","a","b"),
                List.of("id","a"), List.of("id"),
                Set.of("idx_x","idx_y","idx_z"), Set.of(), Set.of("idx_x","idx_y"),
                0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        List<String> lines = r.diffLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("missing cols")),  "Expected missing cols line, got: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("pk mismatch")),   "Expected pk mismatch line, got: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("idx missing in TiDB")), "Expected idx missing line, got: " + lines);
    }

    @Test
    void mismatchPlusKnownLoss_bothReported() {
        // pk mismatch (true mismatch) + UUID dropped (known loss)
        List<String> cols = List.of("id","uid","val");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("id","uid"), List.of("id"),     // pk mismatch
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("uid", "UUID()"),
                Map.of(),
                Map.of(), Map.of());
        assertTrue(r.isMismatch(), "pk mismatch should still be MISMATCH");
        assertTrue(r.hasKnownLoss());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("pk mismatch")));
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("UUID()")));
    }

    @Test
    void unknownFunctionDefault_dropped_isKnownLoss() {
        // dbo.fn_score() → no TiDB default (dropped) should be LOSS, not MISMATCH
        List<String> cols = List.of("id", "score");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("score", "(dbo.fn_score())"),
                Map.of(),    // TiDB has no default (dropped)
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "Dropped unknown-function default must not cause MISMATCH, diffLines=" + r.diffLines());
        assertTrue(r.hasKnownLoss(), "Dropped default must be reported as LOSS");
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("score")),
                "Loss line must mention the column: " + r.knownLossLines());
    }

    @Test
    void getdateMapping_currentTimestamp_isNotMismatch_notLoss() {
        // GETDATE() → CURRENT_TIMESTAMP is a known-safe mapping; must be neither MISMATCH nor LOSS
        List<String> cols = List.of("id", "created_at");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("created_at", "CURRENT_TIMESTAMP"),
                Map.of("created_at", "CURRENT_TIMESTAMP"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(), "GETDATE()→CURRENT_TIMESTAMP must not be MISMATCH");
        assertFalse(r.hasKnownLoss(), "GETDATE()→CURRENT_TIMESTAMP must not be LOSS");
        assertTrue(r.knownLossLines().isEmpty());
    }

    @Test
    void droppedFunctionDefault_withTypeHint_showsTypeInLabel() {
        // When msColTypes / tidbColTypes are provided, defaultLabel should include type hint
        List<String> cols = List.of("id", "score");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("score", "UUID()"),
                Map.of(),   // dropped
                Map.of("score", "uniqueidentifier"),
                Map.of("score", "VARCHAR(36)"));
        assertFalse(r.isMismatch());
        assertTrue(r.hasKnownLoss());
        // The knownLossLines entry should include the type hint
        assertTrue(r.knownLossLines().stream().anyMatch(l ->
                l.contains("score") && l.contains("uniqueidentifier") && l.contains("VARCHAR(36)")),
                "Loss line must include type hint: " + r.knownLossLines());
    }
}
