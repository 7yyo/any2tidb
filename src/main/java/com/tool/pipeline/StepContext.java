package com.tool.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared data bus threaded through each MigrationStep.
 * Steps read inputs and write outputs here instead of returning
 * complex objects or relying on shared mutable state in App.
 */
public class StepContext {
    private final Map<String, Object> store = new HashMap<>();

    public void put(String key, Object value) {
        store.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = store.get(key);
        return v == null ? null : type.cast(v);
    }

    public boolean has(String key) {
        return store.containsKey(key);
    }
}
