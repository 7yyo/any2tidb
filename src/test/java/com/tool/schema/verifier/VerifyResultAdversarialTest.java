package com.tool.schema.verifier;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for VerifyResult — targets edge cases in mismatch/loss
 * detection logic, default comparison, and normalization.
 */
class VerifyResultAdversarialTest {

    private VerifyResult basic() {
        List<String> cols = List.of("id", "name", "status");
        return new VerifyResult(
                "dbo.users", 3, 3,
                cols, cols,
                List.of("id"), List.of("id"),
                Set.of("ix_name"), Set.of(), Set.of("ix_name"),
                0, 0, 2, 2, "id", "id",
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    // ── defaultsEqual: CURRENT_TIMESTAMP(0) vs CURRENT_TIMESTAMP(6) ────────

    @Test
    void defaultsEqual_currentTimestamp0_vs_6() {
        List<String> cols = List.of("id", "ts");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("ts", "CURRENT_TIMESTAMP(0)"),
                Map.of("ts", "CURRENT_TIMESTAMP(6)"),
                Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "CURRENT_TIMESTAMP(0) vs CURRENT_TIMESTAMP(6) should be equivalent");
    }

    // ── defaultsEqual: null vs blank ────────────────────────────────────────

    @Test
    void defaultsEqual_nullVsBlank_areEqual() {
        List<String> cols = List.of("id", "note");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of(),           // MS: no default
                Map.of("note", " "), // TiDB: blank string
                Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "null vs blank default should be equivalent (both mean 'no default')");
    }

    // ── defaultsEqual: single-quoted empty string vs empty ──────────────────

    @Test
    void defaultsEmptyString_quoted_vs_unquoted() {
        List<String> cols = List.of("id", "note");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("note", "''"),  // MS: quoted empty string
                Map.of("note", ""),    // TiDB: unquoted empty string
                Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "Quoted empty string '' vs unquoted empty string should be equivalent");
    }

    // ── isKnownConversion: "NULL" vs null/blank ──────────────────────────────

    @Test
    void isKnownConversion_nullString_vsNull_isKnownConversion() {
        List<String> cols = List.of("id", "flag");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("flag", "NULL"),  // MS: explicit NULL default
                Map.of(),               // TiDB: no default (nullable)
                Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "Explicit NULL default vs no default should be known conversion");
        assertFalse(r.hasKnownLoss(),
                "NULL → no default is a silent conversion, not LOSS");
    }

    // ── isKnownConversion: msD=null → not known conversion ──────────────────

    @Test
    void isKnownConversion_msNull_notKnownConversion() {
        List<String> cols = List.of("id", "val");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of(),            // MS: no default
                Map.of("val", "42"), // TiDB: has default 42
                Map.of(), Map.of());
        // MS has no default, TiDB has "42" → true mismatch
        assertTrue(r.isMismatch(),
                "MS has no default but TiDB has '42' — should be mismatch");
    }

    // ── isDroppedDefault: msD=blank string after normalize → not dropped ───

    @Test
    void isDroppedDefault_msBlank_notDropped() {
        List<String> cols = List.of("id", "val");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("val", "  "),  // MS: blank after trim → normalize returns null
                Map.of(),            // TiDB: no default
                Map.of(), Map.of());
        assertFalse(r.hasKnownLoss(),
                "Blank MS default normalizes to null → not a dropped default");
    }

    // ── isDroppedDefault: msD=UUID(), tdD=blank → IS dropped ────────────────

    @Test
    void isDroppedDefault_uuidWithBlankTiDB_isDropped() {
        List<String> cols = List.of("id", "uid");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("uid", "UUID()"),
                Map.of("uid", " "),  // TiDB: blank (not null!)
                Map.of(), Map.of());
        assertTrue(r.hasKnownLoss(),
                "UUID() with blank TiDB default should still be LOSS");
        assertFalse(r.isMismatch());
    }

    // ── diffLines: both missing and extra columns ───────────────────────────

    @Test
    void diffLines_missingAndExtraCols_bothReported() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                List.of("a", "b", "c"), List.of("a", "d", "e"),
                List.of("a"), List.of("a"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        List<String> lines = r.diffLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("missing cols")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("extra cols")));
    }

    // ── diffLines: column name case sensitivity ─────────────────────────────

    @Test
    void diffLines_columnNameCase_mismatches() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                List.of("ID", "Name", "Status"), List.of("id", "name", "status"),
                List.of("ID"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        // msColNames() uses LinkedHashSet with exact case → Set equality fails
        assertTrue(r.isMismatch(),
                "Column name case difference should be mismatch (Set equality is case-sensitive)");
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("missing cols") || l.contains("extra cols")));
    }

    // ── diffLines: col order with same set but different order ─────────────

    @Test
    void diffLines_sameColumnsDifferentOrder_reportsOrderMismatch() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3,
                List.of("a", "b", "c"), List.of("a", "c", "b"),
                List.of("a"), List.of("a"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("col order mismatch")));
    }

    // ── Index comparison: case insensitive ──────────────────────────────────

    @Test
    void indexComparison_caseInsensitive() {
        List<String> cols = List.of("a", "b");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("a"), List.of("a"),
                Set.of("IX_Name"), Set.of(), Set.of("ix_name"),  // different case
                0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertFalse(r.isMismatch(),
                "Index name comparison should be case-insensitive");
    }

    // ── Index comparison: extra index in TiDB ───────────────────────────────

    @Test
    void indexComparison_extraInTidb_reportsMismatch() {
        List<String> cols = List.of("a", "b");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("a"), List.of("a"),
                Set.of("ix_a"), Set.of(), Set.of("ix_a", "ix_b"),  // extra ix_b in TiDB
                0, 0, 2, 2, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(r.isMismatch());
        assertTrue(r.diffLines().stream().anyMatch(l -> l.contains("idx extra in TiDB")));
    }

    // ── defaultsEqual: nested parens ─────────────────────────────────────────

    @Test
    void defaultsEqual_deeplyNestedParens() {
        List<String> cols = List.of("id", "val");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("val", "((0))"),  // MS: double-wrapped
                Map.of("val", "0"),      // TiDB: unwrapped
                Map.of(), Map.of());
        // VerifyResult.stripQuotes only strips single quotes, NOT parens.
        // "((0))" after stripQuotes = "((0))" → not equal to "0"
        assertTrue(r.isMismatch(),
                "Double-paren-wrapped '((0))' is NOT stripped by stripQuotes — mismatch with '0'");
    }

    // ── defaultsEqual: N-prefixed string ────────────────────────────────────

    @Test
    void defaultsEqual_nPrefixString() {
        List<String> cols = List.of("id", "country");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("country", "N'CN'"),  // MS: N-prefixed string literal
                Map.of("country", "CN"),      // TiDB: unwrapped
                Map.of(), Map.of());
        // stripQuotes handles '...' but NOT N'...' — it sees N'CN' and doesn't strip
        // because it starts with N, not '
        // BUG: N'CN' is not recognized as a quoted string by stripQuotes
        assertTrue(r.isMismatch(),
                "N'CN' is not recognized as quoted by stripQuotes — should be mismatch (known limitation)");
    }

    // ── hasKnownLoss: dropped index + dropped default ───────────────────────

    @Test
    void hasKnownLoss_droppedIndexAndDroppedDefault() {
        List<String> cols = List.of("id", "uid", "data");
        VerifyResult r = new VerifyResult(
                "dbo.t", 3, 3, cols, cols,
                List.of("id"), List.of("id"),
                Set.of("ix_id"), Set.of("IX_cs_data"), Set.of("ix_id"),
                0, 0, 2, 2, null, null,
                Map.of("uid", "UUID()"),
                Map.of(),
                Map.of(), Map.of());
        assertTrue(r.hasKnownLoss());
        List<String> lossLines = r.knownLossLines();
        assertTrue(lossLines.stream().anyMatch(l -> l.contains("IX_cs_data")));
        assertTrue(lossLines.stream().anyMatch(l -> l.contains("uid") && l.contains("UUID()")));
    }

    // ── zero columns on both sides ──────────────────────────────────────────

    @Test
    void zeroColumns_noMismatch() {
        VerifyResult r = new VerifyResult(
                "dbo.t", 0, 0,
                List.of(), List.of(),
                List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), 0, 0, 0, 0, null, null,
                Map.of(), Map.of(), Map.of(), Map.of());
        assertFalse(r.isMismatch());
        assertTrue(r.diffLines().isEmpty());
        assertFalse(r.hasKnownLoss());
    }

    // ── defaultLabel: with type hint ────────────────────────────────────────

    @Test
    void defaultLabel_withTypeHint() {
        List<String> cols = List.of("id", "ts");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("ts", "UTC_TIMESTAMP()"),
                Map.of("ts", "CURRENT_TIMESTAMP(6)"),
                Map.of("ts", "datetimeoffset"),
                Map.of("ts", "DATETIME"));
        // UTC_TIMESTAMP → CURRENT_TIMESTAMP is known conversion, not mismatch
        assertFalse(r.isMismatch());
        assertFalse(r.hasKnownLoss());
    }

    // ── defaultLabel: without type hint ────────────────────────────────────

    @Test
    void defaultLabel_withoutTypeHint() {
        List<String> cols = List.of("id", "val");
        VerifyResult r = new VerifyResult(
                "dbo.t", 2, 2, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 2, 2, null, null,
                Map.of("val", "UUID()"),
                Map.of(),
                Map.of(), Map.of());  // no type info
        assertTrue(r.hasKnownLoss());
        assertTrue(r.knownLossLines().stream().anyMatch(l -> l.contains("default[val]")),
                "Loss line should have default[val] label even without type hint");
    }

    // ── Multiple defaults: some match, some mismatch, some dropped ──────────

    @Test
    void mixedDefaultStates_correctClassification() {
        List<String> cols = List.of("id", "ts", "uid", "note");
        VerifyResult r = new VerifyResult(
                "dbo.t", 4, 4, cols, cols,
                List.of("id"), List.of("id"),
                Set.of(), Set.of(), Set.of(), 0, 0, 3, 3, null, null,
                Map.of(
                        "ts", "CURRENT_TIMESTAMP",   // matches TiDB
                        "uid", "UUID()",              // dropped → LOSS
                        "note", "((0))"               // mismatch: parens not stripped
                ),
                Map.of(
                        "ts", "CURRENT_TIMESTAMP(6)",  // matches (precision ignored)
                        "note", "0"                   // TiDB has "0"
                ),
                Map.of(), Map.of());
        // UUID() is LOSS, ((0)) vs 0 is MISMATCH
        assertTrue(r.hasKnownLoss(), "UUID() should be LOSS");
        assertTrue(r.isMismatch(), "((0)) vs 0 should be MISMATCH");
        // CURRENT_TIMESTAMP should NOT appear in either
        assertTrue(r.diffLines().stream().noneMatch(l -> l.contains("ts")));
        assertTrue(r.knownLossLines().stream().noneMatch(l -> l.contains("ts")));
    }
}
