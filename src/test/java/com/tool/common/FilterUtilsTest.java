package com.tool.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterUtilsTest {

    // ── No filter ──────────────────────────────────────────────────────────

    @Test
    void noFilterWhenNull() {
        List<String> names = List.of("HRDB", "SalesDB", "InventoryDB");
        assertSame(names, FilterUtils.filterNames(names, null));
    }

    @Test
    void noFilterWhenEmpty() {
        List<String> names = List.of("HRDB", "SalesDB");
        assertSame(names, FilterUtils.filterNames(names, List.of()));
    }

    // ── Single database match ──────────────────────────────────────────────

    @Test
    void singleFilterMatch() {
        List<String> names = List.of("HRDB", "SalesDB", "InventoryDB");
        List<String> result = FilterUtils.filterNames(names, List.of("HRDB"));
        assertEquals(List.of("HRDB"), result);
    }

    @Test
    void singleFilterNoMatch() {
        List<String> names = List.of("HRDB", "SalesDB");
        List<String> result = FilterUtils.filterNames(names, List.of("NonExistentDB"));
        assertTrue(result.isEmpty());
    }

    // ── Multiple databases ─────────────────────────────────────────────────

    @Test
    void multiFilterMatch() {
        List<String> names = List.of("HRDB", "SalesDB", "InventoryDB", "FinanceDB");
        List<String> result = FilterUtils.filterNames(names, List.of("HRDB", "FinanceDB"));
        assertEquals(List.of("HRDB", "FinanceDB"), result);
    }

    @Test
    void multiFilterPartialMatch() {
        List<String> names = List.of("HRDB", "SalesDB");
        List<String> result = FilterUtils.filterNames(names, List.of("HRDB", "NonExistentDB"));
        assertEquals(List.of("HRDB"), result);
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    void emptyNamesList() {
        List<String> result = FilterUtils.filterNames(List.of(), List.of("HRDB"));
        assertTrue(result.isEmpty());
    }

    @Test
    void filterIsSupersetOfNames() {
        List<String> names = List.of("HRDB");
        List<String> result = FilterUtils.filterNames(names, List.of("HRDB", "SalesDB", "ExtraDB"));
        assertEquals(List.of("HRDB"), result);
    }

    @Test
    void preservesOrder() {
        List<String> names = List.of("a", "b", "c", "d", "e");
        List<String> filter = List.of("e", "c", "a");
        List<String> result = FilterUtils.filterNames(names, filter);
        assertEquals(List.of("a", "c", "e"), result);
    }

    @Test
    void caseSensitive() {
        List<String> names = List.of("HRDB", "hrdb", "HrDb");
        List<String> result = FilterUtils.filterNames(names, List.of("HRDB"));
        assertEquals(List.of("HRDB"), result);
    }

    @Test
    void filterWithDuplicateEntries() {
        List<String> names = List.of("a", "b", "c");
        List<String> result = FilterUtils.filterNames(names, List.of("a", "a", "b"));
        assertEquals(List.of("a", "b"), result);
    }
}
