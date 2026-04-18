package com.tool.verifier;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVerifierTest {

    /** 构造一个完全匹配的 VerifyResult */
    private VerifyResult ok() {
        List<String> cols = List.of("id", "name", "email", "age", "flag");
        return new VerifyResult(
                "dbo.users",
                5, 5,
                cols, cols,
                List.of("id"), List.of("id"),
                2, 2,
                0, 0,       // msChecks, tidbChecks — not part of mismatch
                3, 3,
                "id", "id",
                Map.of(), Map.of()
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
    void colCountDiff_isMismatch() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 5, 4,
                List.of("id","a","b","c","d"), List.of("id","a","b","c"),
                List.of("id"), List.of("id"),
                1, 1, 0, 0, 3, 3, null, null,
                Map.of(), Map.of());
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
                1, 1, 0, 0, 2, 2, null, null,
                Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("extra cols")));
    }

    @Test
    void colOrderMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                List.of("id","a","b"), List.of("id","b","a"),   // 顺序不同
                List.of("id"), List.of("id"),
                1, 1, 0, 0, 2, 2, null, null,
                Map.of(), Map.of());
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
                1, 1, 0, 0, 2, 2, null, null,
                Map.of(), Map.of());
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
                3, 2, 0, 0, 2, 2, null, null,
                Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx mismatch")));
    }

    @Test
    void checkDiff_isNotMismatch() {
        // CHECK 不参与 mismatch 判定：TiDB 约束已在迁移时丢弃
        List<String> cols = List.of("a","b","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                1, 1, 2, 0, 2, 2, null, null,    // msChecks=2, tidbChecks=0
                Map.of(), Map.of());
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
                1, 1, 0, 0, 3, 2, null, null,
                Map.of(), Map.of());
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
                1, 1, 0, 0, 2, 2, "id", null,
                Map.of(), Map.of());
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
                1, 1, 0, 0, 2, 2, null, null,
                Map.of("b", "0"),          // MS default for col b = 0
                Map.of("b", "1"));         // TiDB default for col b = 1
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("default[b]")));
    }

    @Test
    void defaultWithQuotes_isNotMismatch() {
        // SS stores string defaults as 'CN', TiDB returns CN — should be equal
        List<String> cols = List.of("a","country","c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                1, 1, 0, 0, 2, 2, null, null,
                Map.of("country", "'CN'"),   // MS: with single quotes
                Map.of("country", "CN"));    // TiDB: without quotes
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void defaultCaseInsensitive_isNotMismatch() {
        // CURRENT_TIMESTAMP (MS) vs current_timestamp (TiDB) → should be equal
        List<String> cols = List.of("a", "created_at", "c");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                cols, cols,
                List.of("a"), List.of("a"),
                1, 1, 0, 0, 2, 2, null, null,
                Map.of("created_at", "CURRENT_TIMESTAMP"),
                Map.of("created_at", "current_timestamp"));
        assertFalse(r.isMismatch(), "Case-only difference in defaults should not be a mismatch");
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void nullVsEmpty_defaultsAreEqual() {
        // no defaults on either side → equal
        List<String> cols = List.of("a", "b");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2,
                cols, cols,
                List.of("a"), List.of("a"),
                1, 1, 0, 0, 1, 1, null, null,
                Map.of(),
                Map.of());
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }

    @Test
    void multipleMismatches_allReportedInDiffLines() {
        // Simultaneous: col count diff + pk diff + idx diff
        VerifyResult r = new VerifyResult(
                "dbo.t", 4, 3,
                List.of("id","a","b","c"), List.of("id","a","b"),
                List.of("id","a"), List.of("id"),       // pk mismatch
                3, 2,                                    // idx mismatch
                0, 0, 2, 2, null, null,
                Map.of(), Map.of());
        assertTrue(r.isMismatch());
        List<String> lines = r.diffLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("missing cols")), "Expected missing cols line, got: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("pk mismatch")),  "Expected pk mismatch line, got: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("idx mismatch")), "Expected idx mismatch line, got: " + lines);
    }
}
