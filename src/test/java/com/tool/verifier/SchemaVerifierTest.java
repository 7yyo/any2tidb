package com.tool.verifier;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVerifierTest {

    private VerifyResult ok() {
        return new VerifyResult(
                "dbo.users",
                5, 5,
                Set.of("id", "name", "email", "age", "flag"),
                Set.of("id", "name", "email", "age", "flag"),
                List.of("id"),
                List.of("id"),
                2, 2,
                1,          // msFk — not part of mismatch
                3, 3,
                "id", "id"
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
                Set.of("id","a","b","c","d"), Set.of("id","a","b","c"),
                List.of("id"), List.of("id"),
                1, 1, 0, 3, 3, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols")));
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("d")));
    }

    @Test
    void extraColInTidb_showsInDiffLines() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 4,
                Set.of("id","a","b"), Set.of("id","a","b","extra"),
                List.of("id"), List.of("id"),
                1, 1, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("extra cols")));
    }

    @Test
    void pkMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a","b"), List.of("a"),
                1, 1, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("pk mismatch")));
    }

    @Test
    void idxMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                3, 2, 0, 2, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx mismatch")));
    }

    @Test
    void notNullMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1, 0, 3, 2, null, null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("notnull mismatch")));
    }

    @Test
    void aiMismatch_isMismatchAndReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1, 0, 2, 2, "id", null);
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("ai mismatch")));
    }

    @Test
    void fkDiff_isNotMismatch() {
        // fk 不参与 mismatch 判定
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                Set.of("a","b","c"), Set.of("a","b","c"),
                List.of("a"), List.of("a"),
                1, 1,
                3,   // msFk=3 — should NOT cause mismatch
                2, 2, null, null);
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
    }
}
