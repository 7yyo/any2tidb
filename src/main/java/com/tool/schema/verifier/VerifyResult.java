package com.tool.schema.verifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplified verify result: only checks tables, columns, pk, auto_increment.
 * An empty mismatch list means OK.
 */
public record VerifyResult(
        String fullTableName,
        List<Mismatch> mismatches
) {
    public record Mismatch(String reason, String src, String tidb) {}

    public boolean isOk() { return mismatches.isEmpty(); }

    public static Builder builder(String fullTableName) {
        return new Builder(fullTableName);
    }

    public static class Builder {
        private final String fullTableName;
        private final List<Mismatch> mismatches = new ArrayList<>();

        Builder(String fullTableName) { this.fullTableName = fullTableName; }

        public Builder check(String reason, boolean ok, String src, String tidb) {
            if (!ok) mismatches.add(new Mismatch(reason, src, tidb));
            return this;
        }

        public VerifyResult build() { return new VerifyResult(fullTableName, List.copyOf(mismatches)); }
    }
}
