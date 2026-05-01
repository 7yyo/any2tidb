package com.tool.common;

import java.util.List;

/** Name-list filtering helpers for --databases / --tables CLI options. */
public final class FilterUtils {
    private FilterUtils() {}

    /**
     * Filter {@code names} to only those present in {@code filter}.
     * If filter is null or empty, returns {@code names} unchanged.
     */
    public static List<String> filterNames(List<String> names, List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return names;
        }
        return names.stream().filter(filter::contains).toList();
    }
}
