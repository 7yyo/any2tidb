# Pipeline Architecture Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose the 507-line `App.java` god class into a Pipeline + Step architecture with domain-grouped packages, so future features (e.g. snapshot/incremental migration) can be added as new Steps and new domain packages without touching existing code.

**Architecture:** A `MigrationPipeline` owns an ordered list of `MigrationStep` implementations; each step reads from and writes to a shared `StepContext`. `App.java` shrinks to ~50 lines that wire config, build the pipeline, and call `pipeline.run()`. Schema-migration classes move under `schema/` sub-packages; a skeleton `snapshot/` package is added for the next feature.

**Tech Stack:** Java 17, Spring Boot 3, Maven, JUnit 5. All new interfaces are pure Java (no Spring annotations in the pipeline layer).

---

## File Map

### New files (create)
| File | Responsibility |
|------|---------------|
| `src/main/java/com/tool/pipeline/MigrationStep.java` | Interface: one `execute(StepContext)` method |
| `src/main/java/com/tool/pipeline/StepContext.java` | Shared data bus between steps (replaces local vars in App) |
| `src/main/java/com/tool/pipeline/StepResult.java` | Outcome of a single step execution (status + message) |
| `src/main/java/com/tool/pipeline/MigrationPipeline.java` | Runs steps in order; stops on fatal failure |
| `src/main/java/com/tool/pipeline/steps/PreCheckStep.java` | Validates config before connecting to DBs |
| `src/main/java/com/tool/pipeline/steps/SchemaMigrateStep.java` | Extract → Convert → Write loop (moved from App) |
| `src/main/java/com/tool/pipeline/steps/VerifyStep.java` | Schema verify + summary print (moved from App) |
| `src/main/java/com/tool/output/ProgressReporter.java` | `printProgress()` + `clearProgress()` (extracted from App) |
| `src/main/java/com/tool/output/SummaryPrinter.java` | `printSummary()` + `IssueRow` (extracted from App) |
| `src/main/java/com/tool/logging/StructuredLogger.java` | `flog()` (extracted from App) |
| `src/main/java/com/tool/schema/extractor/SchemaExtractor.java` | Interface: source-agnostic schema extraction contract |
| `src/main/java/com/tool/schema/writer/SchemaWriter.java` | Interface: target-agnostic DDL write contract |
| `src/main/java/com/tool/snapshot/.gitkeep` | Placeholder — future snapshot (full) migration domain |
| `src/test/java/com/tool/pipeline/MigrationPipelineTest.java` | Unit tests for pipeline step sequencing |
| `src/test/java/com/tool/pipeline/steps/PreCheckStepTest.java` | Unit tests for pre-check validation |

### Moved files (package rename only — move source file, update `package` declaration and all `import` statements)
| From | To |
|------|----|
| `src/main/java/com/tool/converter/SchemaConverter.java` | `src/main/java/com/tool/schema/converter/SchemaConverter.java` |
| `src/main/java/com/tool/converter/TypeMapper.java` | `src/main/java/com/tool/schema/converter/TypeMapper.java` |
| `src/main/java/com/tool/extractor/SqlServerExtractor.java` | `src/main/java/com/tool/schema/extractor/SqlServerExtractor.java` |
| `src/main/java/com/tool/writer/TiDBWriter.java` | `src/main/java/com/tool/schema/writer/TiDBWriter.java` |
| `src/main/java/com/tool/verifier/SchemaVerifier.java` | `src/main/java/com/tool/schema/verifier/SchemaVerifier.java` |
| `src/main/java/com/tool/verifier/VerifyResult.java` | `src/main/java/com/tool/schema/verifier/VerifyResult.java` |
| `src/main/java/com/tool/model/ColumnSchema.java` | `src/main/java/com/tool/common/model/ColumnSchema.java` |
| `src/main/java/com/tool/model/IndexSchema.java` | `src/main/java/com/tool/common/model/IndexSchema.java` |
| `src/main/java/com/tool/model/TableSchema.java` | `src/main/java/com/tool/common/model/TableSchema.java` |
| `src/main/java/com/tool/ConversionResult.java` | `src/main/java/com/tool/common/model/ConversionResult.java` |

### Modified files (significant changes)
| File | Change |
|------|--------|
| `src/main/java/com/tool/App.java` | Slim to ~50 lines; wire pipeline |
| All test files under `com/tool/converter/`, `com/tool/extractor/`, `com/tool/writer/`, `com/tool/verifier/` | Update `package` + `import` to new locations |

---

## Task 1: Pipeline interfaces (`MigrationStep`, `StepContext`, `StepResult`)

**Files:**
- Create: `src/main/java/com/tool/pipeline/MigrationStep.java`
- Create: `src/main/java/com/tool/pipeline/StepResult.java`
- Create: `src/main/java/com/tool/pipeline/StepContext.java`
- Create: `src/test/java/com/tool/pipeline/MigrationPipelineTest.java` (partial — extended in Task 2)

- [ ] **Step 1: Write failing test for StepContext and StepResult**

Create `src/test/java/com/tool/pipeline/MigrationPipelineTest.java`:

```java
package com.tool.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationPipelineTest {

    @Test
    void stepResult_ok_isNotFatal() {
        StepResult r = StepResult.ok("done");
        assertFalse(r.isFatal());
        assertEquals("done", r.message());
    }

    @Test
    void stepResult_fatal_isFatal() {
        StepResult r = StepResult.fatal("boom");
        assertTrue(r.isFatal());
        assertEquals("boom", r.message());
    }

    @Test
    void stepContext_storeAndRetrieve() {
        StepContext ctx = new StepContext();
        ctx.put("key", "value");
        assertEquals("value", ctx.get("key", String.class));
    }

    @Test
    void stepContext_getMissing_returnsNull() {
        StepContext ctx = new StepContext();
        assertNull(ctx.get("missing", String.class));
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=MigrationPipelineTest -q 2>&1 | tail -5
```

Expected: compilation error — `StepResult`, `StepContext` not found.

- [ ] **Step 3: Create `StepResult.java`**

```java
package com.tool.pipeline;

public record StepResult(boolean isFatal, String message) {
    public static StepResult ok(String message)    { return new StepResult(false, message); }
    public static StepResult fatal(String message) { return new StepResult(true,  message); }
}
```

Save to `src/main/java/com/tool/pipeline/StepResult.java`.

- [ ] **Step 4: Create `StepContext.java`**

```java
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
```

Save to `src/main/java/com/tool/pipeline/StepContext.java`.

- [ ] **Step 5: Create `MigrationStep.java`**

```java
package com.tool.pipeline;

/**
 * One discrete stage of the migration pipeline.
 * Implementations read what they need from {@code ctx} and write
 * their outputs back into it for downstream steps to consume.
 */
@FunctionalInterface
public interface MigrationStep {
    /**
     * @return {@link StepResult#ok} on success (pipeline continues),
     *         {@link StepResult#fatal} on unrecoverable failure (pipeline stops).
     */
    StepResult execute(StepContext ctx) throws Exception;

    /** Human-readable name shown in logs. Override for clearer output. */
    default String name() { return getClass().getSimpleName(); }
}
```

Save to `src/main/java/com/tool/pipeline/MigrationStep.java`.

- [ ] **Step 6: Run test — expect green**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=MigrationPipelineTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 4 tests pass.

- [ ] **Step 7: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/pipeline/MigrationStep.java \
        src/main/java/com/tool/pipeline/StepResult.java \
        src/main/java/com/tool/pipeline/StepContext.java \
        src/test/java/com/tool/pipeline/MigrationPipelineTest.java
git commit -m "feat: introduce MigrationStep, StepContext, StepResult pipeline interfaces"
```

---

## Task 2: `MigrationPipeline` orchestrator

**Files:**
- Create: `src/main/java/com/tool/pipeline/MigrationPipeline.java`
- Modify: `src/test/java/com/tool/pipeline/MigrationPipelineTest.java` (add pipeline tests)

- [ ] **Step 1: Add pipeline tests to existing test file**

Append these tests inside `MigrationPipelineTest` class (before the closing `}`):

```java
    @Test
    void pipeline_runsStepsInOrder() throws Exception {
        List<String> log = new java.util.ArrayList<>();
        MigrationStep s1 = ctx -> { log.add("s1"); return StepResult.ok("step1 done"); };
        MigrationStep s2 = ctx -> { log.add("s2"); return StepResult.ok("step2 done"); };

        new MigrationPipeline(List.of(s1, s2)).run(new StepContext());

        assertEquals(List.of("s1", "s2"), log);
    }

    @Test
    void pipeline_stopOnFatal() throws Exception {
        List<String> log = new java.util.ArrayList<>();
        MigrationStep s1 = ctx -> { log.add("s1"); return StepResult.fatal("fatal!"); };
        MigrationStep s2 = ctx -> { log.add("s2"); return StepResult.ok("ok"); };

        new MigrationPipeline(List.of(s1, s2)).run(new StepContext());

        assertEquals(List.of("s1"), log, "s2 must not run after fatal");
    }

    @Test
    void pipeline_emptySteps_runsOk() throws Exception {
        // should not throw
        assertDoesNotThrow(() ->
            new MigrationPipeline(java.util.List.of()).run(new StepContext()));
    }
```

Also add the import at the top of the file:

```java
import java.util.List;
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=MigrationPipelineTest -q 2>&1 | tail -5
```

Expected: compilation error — `MigrationPipeline` not found.

- [ ] **Step 3: Create `MigrationPipeline.java`**

```java
package com.tool.pipeline;

import java.util.List;

/**
 * Runs a fixed sequence of {@link MigrationStep}s against a shared {@link StepContext}.
 * Stops immediately when any step returns {@link StepResult#isFatal()}.
 */
public class MigrationPipeline {

    private final List<MigrationStep> steps;

    public MigrationPipeline(List<MigrationStep> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * Execute all steps in order.
     * @param ctx shared context threaded through every step
     * @return the result of the last step executed
     */
    public StepResult run(StepContext ctx) throws Exception {
        StepResult last = StepResult.ok("no steps");
        for (MigrationStep step : steps) {
            last = step.execute(ctx);
            if (last.isFatal()) break;
        }
        return last;
    }
}
```

Save to `src/main/java/com/tool/pipeline/MigrationPipeline.java`.

- [ ] **Step 4: Run all pipeline tests — expect green**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=MigrationPipelineTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/pipeline/MigrationPipeline.java \
        src/test/java/com/tool/pipeline/MigrationPipelineTest.java
git commit -m "feat: add MigrationPipeline orchestrator"
```

---

## Task 3: Extract `StructuredLogger` from `App.java`

**Files:**
- Create: `src/main/java/com/tool/logging/StructuredLogger.java`
- Test: no unit test (pure I/O side-effect; behavior stays identical)

The `flog()` method in `App.java` (lines 453-469) is a standalone utility with no dependencies on App state other than the `PrintWriter fileLog`. Extract it as a class.

- [ ] **Step 1: Create `StructuredLogger.java`**

```java
package com.tool.logging;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes structured log lines to a file (append mode).
 * Format: [timestamp] [LEVEL] ["message"] [key=value] ...
 * All writes go to the file only — never to stdout.
 */
public class StructuredLogger implements AutoCloseable {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS Z");

    private final PrintWriter out;

    /** Open (or create) the given log file in append mode. */
    public static StructuredLogger open(String path) {
        try {
            return new StructuredLogger(new PrintWriter(new FileWriter(path, true), true));
        } catch (Exception e) {
            // Fall back to /dev/null — logging is best-effort
            return new StructuredLogger(new PrintWriter(new java.io.OutputStream() {
                public void write(int b) {}
            }));
        }
    }

    private StructuredLogger(PrintWriter out) {
        this.out = out;
    }

    /**
     * Write one log line.
     * @param level   e.g. "INFO", "WARN", "ERROR"
     * @param message free-text message
     * @param fields  alternating key-value pairs (must be even count)
     */
    public void log(String level, String message, Object... fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ZonedDateTime.now().format(TS)).append(']');
        sb.append(" [").append(level).append(']');
        sb.append(" [\"").append(message).append("\"]");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String k = String.valueOf(fields[i]);
            String v = String.valueOf(fields[i + 1]);
            boolean quote = v.contains(" ") || v.isEmpty();
            sb.append(" [").append(k).append('=');
            if (quote) sb.append('"').append(v).append('"');
            else       sb.append(v);
            sb.append(']');
        }
        out.println(sb);
    }

    @Override
    public void close() {
        out.close();
    }
}
```

Save to `src/main/java/com/tool/logging/StructuredLogger.java`.

- [ ] **Step 2: Verify project compiles**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` (no changes to App yet — just a new class).

- [ ] **Step 3: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/logging/StructuredLogger.java
git commit -m "feat: extract StructuredLogger from App"
```

---

## Task 4: Extract `ProgressReporter` and `SummaryPrinter` from `App.java`

**Files:**
- Create: `src/main/java/com/tool/output/ProgressReporter.java`
- Create: `src/main/java/com/tool/output/SummaryPrinter.java`
- No tests — pure console I/O; tested implicitly by integration tests later

`ProgressReporter` wraps `printProgress()` + `clearProgress()` (App lines 485-506).  
`SummaryPrinter` wraps `printSummary()` + `IssueRow` (App lines 321-445).

- [ ] **Step 1: Create `ProgressReporter.java`**

```java
package com.tool.output;

/**
 * Renders an in-place progress bar on stdout using carriage return (\r).
 * Thread-unsafe — call only from the main thread.
 */
public class ProgressReporter {

    private int lastProgressLen = 0;
    private int dbNameWidth = 0;

    public void setDbNameWidth(int width) {
        this.dbNameWidth = width;
    }

    /**
     * Overwrite the current console line with a progress bar.
     *
     * @param dbName    database being converted
     * @param done      tables completed so far
     * @param total     total tables in this database
     * @param skipCount number of skipped tables (unused in bar, reserved for future)
     * @param current   name of the table currently being processed
     */
    public void print(String dbName, int done, int total, int skipCount, String current) {
        int barWidth = 20;
        int filled = (total == 0) ? barWidth : (int) Math.round((double) done / total * barWidth);
        String bar = "━".repeat(filled) + "─".repeat(barWidth - filled);
        String line = String.format(" %-" + dbNameWidth + "s  [%s]  %d/%d  %s",
                dbName, bar, done, total, current);
        if (line.length() < lastProgressLen) {
            line = line + " ".repeat(lastProgressLen - line.length());
        }
        lastProgressLen = line.length();
        System.out.print("\r" + line);
        System.out.flush();
    }

    /** Erase the progress line — call before printing multi-line output. */
    public void clear() {
        if (lastProgressLen > 0) {
            System.out.print("\r" + " ".repeat(lastProgressLen) + "\r");
            System.out.flush();
            lastProgressLen = 0;
        }
    }
}
```

Save to `src/main/java/com/tool/output/ProgressReporter.java`.

- [ ] **Step 2: Create `SummaryPrinter.java`**

```java
package com.tool.output;

import com.tool.common.model.ConversionResult;
import com.tool.schema.verifier.VerifyResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
                    .filter(r -> kind.equals(r.kind())).collect(Collectors.toList());
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
```

Save to `src/main/java/com/tool/output/SummaryPrinter.java`.

- [ ] **Step 3: Compile — expect failure because `com.tool.common.model.ConversionResult` doesn't exist yet**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -q 2>&1 | grep "ERROR" | head -10
```

Expected: error about `com.tool.common.model.ConversionResult` (will be fixed in Task 5).

- [ ] **Step 4: Commit what we have so far (even with compile error)**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/output/ProgressReporter.java \
        src/main/java/com/tool/output/SummaryPrinter.java
git commit -m "feat: extract ProgressReporter and SummaryPrinter from App (compile pending model move)"
```

---

## Task 5: Move models to `common/model/` and `schema/verifier/`

This is the pure mechanical move: update package declarations and all import statements. No logic changes.

**Files moved:**
- `com.tool.model.*` → `com.tool.common.model.*` (ColumnSchema, IndexSchema, TableSchema, ConversionResult)
- `com.tool.verifier.*` → `com.tool.schema.verifier.*` (SchemaVerifier, VerifyResult)

- [ ] **Step 1: Create `common/model/` versions of the 4 model files**

Copy each file and update only the `package` declaration:

**`src/main/java/com/tool/common/model/ColumnSchema.java`** — copy from `src/main/java/com/tool/model/ColumnSchema.java`, change first line to:
```java
package com.tool.common.model;
```

**`src/main/java/com/tool/common/model/IndexSchema.java`** — copy from `src/main/java/com/tool/model/IndexSchema.java`, change first line to:
```java
package com.tool.common.model;
```

**`src/main/java/com/tool/common/model/TableSchema.java`** — copy from `src/main/java/com/tool/model/TableSchema.java`, change first line to:
```java
package com.tool.common.model;
```
Also update the import on the `ColumnSchema` and `IndexSchema` lines inside TableSchema.java if they have explicit imports (they use simple names in the same old package — now they're in the same new package, no imports needed).

**`src/main/java/com/tool/common/model/ConversionResult.java`** — copy from `src/main/java/com/tool/ConversionResult.java`, change first line to:
```java
package com.tool.common.model;
```

- [ ] **Step 2: Create `schema/verifier/` versions**

**`src/main/java/com/tool/schema/verifier/VerifyResult.java`** — copy from `src/main/java/com/tool/verifier/VerifyResult.java`, change first line to:
```java
package com.tool.schema.verifier;
```

**`src/main/java/com/tool/schema/verifier/SchemaVerifier.java`** — copy from `src/main/java/com/tool/verifier/SchemaVerifier.java`, change first line to:
```java
package com.tool.schema.verifier;
```
Update all imports inside: `com.tool.model.*` → `com.tool.common.model.*`.

- [ ] **Step 3: Move `converter/`, `extractor/`, `writer/` to `schema/` sub-packages**

**`src/main/java/com/tool/schema/converter/TypeMapper.java`** — copy from `src/main/java/com/tool/converter/TypeMapper.java`, update:
```java
package com.tool.schema.converter;
```

**`src/main/java/com/tool/schema/converter/SchemaConverter.java`** — copy from `src/main/java/com/tool/converter/SchemaConverter.java`, update:
```java
package com.tool.schema.converter;
// change import: com.tool.model.* → com.tool.common.model.*
```

**`src/main/java/com/tool/schema/extractor/SqlServerExtractor.java`** — copy from `src/main/java/com/tool/extractor/SqlServerExtractor.java`, update:
```java
package com.tool.schema.extractor;
// change import: com.tool.model.* → com.tool.common.model.*
```

**`src/main/java/com/tool/schema/writer/TiDBWriter.java`** — copy from `src/main/java/com/tool/writer/TiDBWriter.java`, update:
```java
package com.tool.schema.writer;
// change import: com.tool.model.* → com.tool.common.model.*
```

- [ ] **Step 4: Create `SchemaExtractor` interface**

Create `src/main/java/com/tool/schema/extractor/SchemaExtractor.java`:

```java
package com.tool.schema.extractor;

import com.tool.common.model.TableSchema;

import java.sql.Connection;
import java.util.List;

/**
 * Source-agnostic contract for reading schema metadata.
 * Decouple pipeline steps from the SQL Server implementation so future
 * extractors (e.g. PostgresExtractor) can be wired in without touching step code.
 */
public interface SchemaExtractor {
    /** Return all user-database names visible to this connection. */
    List<String> listDatabases(Connection conn) throws Exception;

    /**
     * List tables matching the given schema/name filters.
     * Each entry is a two-element array: [schemaName, tableName].
     * Empty lists mean "no filter" (return all).
     */
    List<String[]> listTables(Connection conn, List<String> schemas, List<String> tables) throws Exception;

    /** Extract full column + index metadata for one table. */
    TableSchema extractTable(Connection conn, String schema, String table) throws Exception;
}
```

- [ ] **Step 5: Add `implements SchemaExtractor` to `SqlServerExtractor`**

In `src/main/java/com/tool/schema/extractor/SqlServerExtractor.java`, update the class declaration from:
```java
public class SqlServerExtractor {
```
to:
```java
public class SqlServerExtractor implements SchemaExtractor {
```

(No other changes — the three public methods already match the interface signatures.)

- [ ] **Step 6: Create `SchemaWriter` interface**

Create `src/main/java/com/tool/schema/writer/SchemaWriter.java`:

```java
package com.tool.schema.writer;

import com.tool.common.model.ConversionResult;

import java.sql.Connection;

/**
 * Target-agnostic contract for applying DDL to a destination database.
 * Decouples pipeline steps from TiDB so future writers (e.g. PostgresWriter)
 * can be wired in without touching step code.
 */
public interface SchemaWriter {
    /** Execute the given DDL against {@code conn}; update {@code result} with outcome. */
    void executeDDL(Connection conn, String ddl, ConversionResult result) throws Exception;

    /** Print DDL to stdout (dry-run mode). */
    void printDDL(String fullName, String ddl);
}
```

- [ ] **Step 7: Add `implements SchemaWriter` to `TiDBWriter`**

In `src/main/java/com/tool/schema/writer/TiDBWriter.java`, update the class declaration from:
```java
public class TiDBWriter {
```
to:
```java
public class TiDBWriter implements SchemaWriter {
```

(No other changes — the two public methods already match the interface signatures.)

- [ ] **Step 9: Compile — expect success (old packages still exist alongside new ones)**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Run full test suite — expect success**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, 146+ tests pass (old tests still use old packages — that's fine for now).

- [ ] **Step 11: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/common/ \
        src/main/java/com/tool/schema/
git commit -m "feat: add schema/ and common/model/ packages with SchemaExtractor/SchemaWriter interfaces (old packages preserved until App wired)"
```

---

## Task 6: Update test files to new package locations

All test files import from old packages (`com.tool.model`, `com.tool.converter`, etc.). Update them to the new packages. Delete the old source packages after updating.

- [ ] **Step 1: Update test package + imports for converter tests**

Edit `src/test/java/com/tool/converter/SchemaConverterTest.java`:
- Change `package com.tool.converter;` → `package com.tool.schema.converter;`
- Change `import com.tool.converter.*;` → `import com.tool.schema.converter.*;`
- Change `import com.tool.model.*;` → `import com.tool.common.model.*;`

Move the file:
```bash
mkdir -p /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/converter
mv /home/sev7nyo/ms2tidb/src/test/java/com/tool/converter/SchemaConverterTest.java \
   /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/converter/SchemaConverterTest.java
```

Edit `src/test/java/com/tool/converter/TypeMapperTest.java`:
- Change `package com.tool.converter;` → `package com.tool.schema.converter;`
- Change `import com.tool.converter.*;` → `import com.tool.schema.converter.*;`

Move the file:
```bash
mv /home/sev7nyo/ms2tidb/src/test/java/com/tool/converter/TypeMapperTest.java \
   /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/converter/TypeMapperTest.java
rmdir /home/sev7nyo/ms2tidb/src/test/java/com/tool/converter 2>/dev/null || true
```

- [ ] **Step 2: Update extractor, writer, verifier test files**

```bash
mkdir -p /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/extractor
mkdir -p /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/writer
mkdir -p /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/verifier
```

**SqlServerExtractorTest.java** — update package to `com.tool.schema.extractor`, imports to `com.tool.schema.extractor.*` and `com.tool.common.model.*`:
```bash
mv /home/sev7nyo/ms2tidb/src/test/java/com/tool/extractor/SqlServerExtractorTest.java \
   /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/extractor/SqlServerExtractorTest.java
rmdir /home/sev7nyo/ms2tidb/src/test/java/com/tool/extractor 2>/dev/null || true
```

**TiDBWriterTest.java** — update package to `com.tool.schema.writer`, imports to `com.tool.schema.writer.*` and `com.tool.common.model.*`:
```bash
mv /home/sev7nyo/ms2tidb/src/test/java/com/tool/writer/TiDBWriterTest.java \
   /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/writer/TiDBWriterTest.java
rmdir /home/sev7nyo/ms2tidb/src/test/java/com/tool/writer 2>/dev/null || true
```

**SchemaVerifierTest.java** — update package to `com.tool.schema.verifier`, imports to `com.tool.schema.verifier.*`:
```bash
mv /home/sev7nyo/ms2tidb/src/test/java/com/tool/verifier/SchemaVerifierTest.java \
   /home/sev7nyo/ms2tidb/src/test/java/com/tool/schema/verifier/SchemaVerifierTest.java
rmdir /home/sev7nyo/ms2tidb/src/test/java/com/tool/verifier 2>/dev/null || true
```

- [ ] **Step 3: Delete old source packages**

```bash
rm -rf /home/sev7nyo/ms2tidb/src/main/java/com/tool/converter
rm -rf /home/sev7nyo/ms2tidb/src/main/java/com/tool/extractor
rm -rf /home/sev7nyo/ms2tidb/src/main/java/com/tool/writer
rm -rf /home/sev7nyo/ms2tidb/src/main/java/com/tool/verifier
rm -rf /home/sev7nyo/ms2tidb/src/main/java/com/tool/model
rm     /home/sev7nyo/ms2tidb/src/main/java/com/tool/ConversionResult.java
```

- [ ] **Step 4: Run full test suite**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, same test count as before (146+). If any test fails, the old package name is still somewhere — grep for it:
```bash
grep -r "com\.tool\.model\|com\.tool\.converter\|com\.tool\.verifier\|com\.tool\.extractor\|com\.tool\.writer" \
     /home/sev7nyo/ms2tidb/src --include="*.java" | grep -v "^Binary"
```

- [ ] **Step 5: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add -A
git commit -m "refactor: move all classes to schema/ and common/model/ packages, update all imports and tests"
```

---

## Task 7: Create `PreCheckStep` (pre-flight validation)

`PreCheckStep` validates config before any DB connection is made. Currently this logic is inline in `App.run()`. The step fails fatally if config is obviously wrong.

**Files:**
- Create: `src/main/java/com/tool/pipeline/steps/PreCheckStep.java`
- Create: `src/test/java/com/tool/pipeline/steps/PreCheckStepTest.java`

`StepContext` keys written by this step:
- `"dryRun"` → `Boolean`
- `"schemas"` → `List<String>`
- `"tables"` → `List<String>`
- `"dropIfExists"` → `Boolean`
- `"continueOnError"` → `Boolean`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/tool/pipeline/steps/PreCheckStepTest.java`:

```java
package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PreCheckStepTest {

    private static AppConfig configWith(String srcHost, String tgtHost) {
        AppConfig cfg = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost(srcHost); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        AppConfig.DbConfig tgt = new AppConfig.DbConfig();
        tgt.setHost(tgtHost); tgt.setPort(4000);
        tgt.setUsername("root"); tgt.setPassword("");
        cfg.setSource(src);
        cfg.setTarget(tgt);
        AppConfig.ConvertConfig cc = new AppConfig.ConvertConfig();
        cc.setSchemas(List.of());
        cc.setTables(List.of());
        cfg.setConvert(cc);
        return cfg;
    }

    @Test
    void validConfig_producesOkResult() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("127.0.0.1", "127.0.0.1")).execute(ctx);

        assertFalse(r.isFatal());
    }

    @Test
    void validConfig_populatesContextKeys() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        new PreCheckStep(configWith("127.0.0.1", "127.0.0.1")).execute(ctx);

        assertNotNull(ctx.get("schemas", List.class));
        assertNotNull(ctx.get("tables", List.class));
        assertFalse((Boolean) ctx.get("dryRun", Boolean.class));
    }

    @Test
    void blankSourceHost_returnsFatal() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("", "127.0.0.1")).execute(ctx);

        assertTrue(r.isFatal());
        assertTrue(r.message().contains("source.host"));
    }

    @Test
    void blankTargetHost_returnsFatal() throws Exception {
        StepContext ctx = new StepContext();
        ctx.put("dryRun", false);
        ctx.put("tablesOverride", (Object) null);

        StepResult r = new PreCheckStep(configWith("127.0.0.1", "")).execute(ctx);

        assertTrue(r.isFatal());
        assertTrue(r.message().contains("target.host"));
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=PreCheckStepTest -q 2>&1 | tail -5
```

Expected: compilation error — `PreCheckStep` not found.

- [ ] **Step 3: Create `PreCheckStep.java`**

```java
package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;

import java.util.List;

/**
 * Pre-flight validation: checks that source and target hosts are configured,
 * then publishes resolved config values into StepContext for downstream steps.
 *
 * Reads from context:
 *   "dryRun"        (Boolean)       — set by App before pipeline.run()
 *   "tablesOverride" (List<String>)  — optional CLI override, may be null
 *
 * Writes to context:
 *   "schemas"        (List<String>)
 *   "tables"         (List<String>)
 *   "dropIfExists"   (Boolean)
 *   "continueOnError" (Boolean)
 */
public class PreCheckStep implements MigrationStep {

    private final AppConfig config;

    public PreCheckStep(AppConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "PreCheck"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) {
        String srcHost = config.getSource().getHost();
        if (srcHost == null || srcHost.isBlank()) {
            return StepResult.fatal("source.host is not configured in application.yml");
        }
        String tgtHost = config.getTarget().getHost();
        if (tgtHost == null || tgtHost.isBlank()) {
            return StepResult.fatal("target.host is not configured in application.yml");
        }

        List<String> tablesOverride = ctx.get("tablesOverride", List.class);
        List<String> schemas = config.getConvert().getSchemas();
        List<String> tables  = tablesOverride != null ? tablesOverride : config.getConvert().getTables();

        ctx.put("schemas",         schemas  != null ? schemas : List.of());
        ctx.put("tables",          tables   != null ? tables  : List.of());
        ctx.put("dropIfExists",    config.getConvert().isDropIfExists());
        ctx.put("continueOnError", config.getConvert().isContinueOnError());

        return StepResult.ok("config validated");
    }
}
```

Save to `src/main/java/com/tool/pipeline/steps/PreCheckStep.java`.

- [ ] **Step 4: Run tests — expect green**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -pl . -Dtest=PreCheckStepTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/pipeline/steps/PreCheckStep.java \
        src/test/java/com/tool/pipeline/steps/PreCheckStepTest.java
git commit -m "feat: add PreCheckStep — validates config before DB connections"
```

---

## Task 8: Create `SchemaMigrateStep` and `VerifyStep`

These two steps contain the real migration logic moved from `App.migrateOneDatabase()` and `App.collectVerifyRows()` / `App.printSummary()`.

**Files:**
- Create: `src/main/java/com/tool/pipeline/steps/SchemaMigrateStep.java`
- Create: `src/main/java/com/tool/pipeline/steps/VerifyStep.java`

`StepContext` keys written by `SchemaMigrateStep`:
- `"dbSummaries"` → `List<SummaryPrinter.DbSummary>`
- `"totalFailed"` → `Integer`

`StepContext` keys read by `VerifyStep`:
- `"dbSummaries"` → `List<SummaryPrinter.DbSummary>`

- [ ] **Step 1: Create `SchemaMigrateStep.java`**

```java
package com.tool.pipeline.steps;

import com.tool.common.model.ConversionResult;
import com.tool.common.model.TableSchema;
import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.writer.SchemaWriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * Connects to SQL Server and TiDB, iterates all selected databases,
 * and for each database: extracts, converts, and writes schema.
 *
 * Reads from context:
 *   "dryRun"          (Boolean)
 *   "schemas"         (List<String>)
 *   "tables"          (List<String>)
 *   "dropIfExists"    (Boolean)
 *   "continueOnError" (Boolean)
 *
 * Writes to context:
 *   "dbSummaries"   (List<SummaryPrinter.DbSummary>)
 *   "totalFailed"   (Integer)
 */
public class SchemaMigrateStep implements MigrationStep {

    private final AppConfig config;
    private final SchemaExtractor extractor;
    private final SchemaConverter converter;
    private final SchemaWriter writer;
    private final StructuredLogger log;
    private final ProgressReporter progress;

    public SchemaMigrateStep(AppConfig config, SchemaExtractor extractor,
                             SchemaConverter converter, SchemaWriter writer,
                             StructuredLogger log, ProgressReporter progress) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.log       = log;
        this.progress  = progress;
    }

    @Override
    public String name() { return "SchemaMigrate"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        boolean dryRun         = Boolean.TRUE.equals(ctx.get("dryRun", Boolean.class));
        List<String> schemas   = ctx.get("schemas", List.class);
        List<String> tables    = ctx.get("tables",  List.class);
        boolean dropIfExists   = Boolean.TRUE.equals(ctx.get("dropIfExists", Boolean.class));
        boolean continueOnError= Boolean.TRUE.equals(ctx.get("continueOnError", Boolean.class));

        List<String> dbNames;
        try (Connection masterConn = DriverManager.getConnection(
                config.getSource().sqlServerJdbcUrlNoDB(),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = extractor.listDatabases(masterConn);
        }
        log.log("INFO", "Found databases", "count", dbNames.size());
        progress.setDbNameWidth(dbNames.stream().mapToInt(String::length).max().orElse(8));

        int totalFailed = 0;
        boolean stoppedEarlyGlobal = false;
        List<SummaryPrinter.DbSummary> summaries = new ArrayList<>();

        for (String dbName : dbNames) {
            try (Connection ssConn = DriverManager.getConnection(
                     config.getSource().sqlServerJdbcUrlForDB(dbName),
                     config.getSource().getUsername(),
                     config.getSource().getPassword());
                 Connection tidbConn = dryRun ? null : openTiDB(dbName)) {

                DbResult dr = migrateOneDb(ssConn, tidbConn, dbName,
                        schemas, tables, dropIfExists, continueOnError, dryRun);
                totalFailed += dr.failed;

                summaries.add(new SummaryPrinter.DbSummary(
                        dbName,
                        dr.allTables.size(),
                        dr.succeededTables.size(),
                        dr.convResults));

                if (dr.stoppedEarly) {
                    progress.clear();
                    System.out.println("[WARN ] Stopped early (continueOnError=false) — see ms2tidb.log");
                    stoppedEarlyGlobal = true;
                    break;
                }
            }
        }

        ctx.put("dbSummaries", summaries);
        ctx.put("totalFailed", totalFailed);

        return stoppedEarlyGlobal
                ? StepResult.fatal("stopped early: continueOnError=false")
                : StepResult.ok("schema migration complete, failed=" + totalFailed);
    }

    // ── Internal result carrier ───────────────────────────────────────────────

    private record DbResult(
            int failed, int warned, int skipped, boolean stoppedEarly,
            List<String[]> allTables, List<String[]> succeededTables,
            Map<String, ConversionResult> convResults) {}

    private DbResult migrateOneDb(Connection ssConn, Connection tidbConn,
                                  String dbName, List<String> schemas, List<String> tables,
                                  boolean dropIfExists, boolean continueOnError,
                                  boolean dryRun) throws Exception {
        List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
        log.log("INFO", "Starting conversion", "db", dbName, "tables", tableList.size());

        // Pre-check: table name conflicts across schemas
        Map<String, List<String>> nameToSchemas = new LinkedHashMap<>();
        for (String[] entry : tableList) {
            nameToSchemas.computeIfAbsent(entry[1], k -> new ArrayList<>()).add(entry[0]);
        }
        List<String> conflicts = nameToSchemas.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "  \"" + e.getKey() + "\"  →  " + String.join(", ",
                        e.getValue().stream().map(s -> s + "." + e.getKey()).toList()))
                .toList();
        if (!conflicts.isEmpty()) {
            progress.clear();
            System.out.println("[ERROR] Table name conflict in [" + dbName + "] — see ms2tidb.log");
            log.log("ERROR", "Table name conflict", "db", dbName);
            conflicts.forEach(line -> log.log("ERROR", "conflict", "tables", line));
            return new DbResult(1, 0, 0, true, tableList, List.of(), Map.of());
        }

        int total = tableList.size();
        int succeeded = 0, warned = 0, failed = 0, skipped = 0;
        List<String[]> succeededTables = new ArrayList<>();
        Map<String, ConversionResult> convResults = new LinkedHashMap<>();
        boolean stopEarly = false;

        for (int i = 0; i < total; i++) {
            String[] entry      = tableList.get(i);
            String schemaName   = entry[0];
            String tableName    = entry[1];
            String fullName     = schemaName + "." + tableName;

            progress.print(dbName, i, total, skipped, fullName);
            ConversionResult result = new ConversionResult(fullName);

            try {
                TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);
                if (ddl == null) {
                    // result already has ERROR set by converter
                } else if (dryRun) {
                    writer.printDDL(fullName, ddl);
                } else {
                    writer.executeDDL(tidbConn, ddl, result);
                }
            } catch (Exception e) {
                result.setError(e.getMessage());
            }

            switch (result.getStatus()) {
                case OK -> { succeeded++; succeededTables.add(entry); convResults.put(fullName, result); }
                case WARN -> { succeeded++; warned++; succeededTables.add(entry); convResults.put(fullName, result); }
                case ERROR -> { failed++; convResults.put(fullName, result); if (!continueOnError) stopEarly = true; }
                case SKIP  -> { skipped++; convResults.put(fullName, result); }
            }
            if (stopEarly) break;
        }

        progress.print(dbName, total, total, skipped, "");
        System.out.println();

        return new DbResult(failed, warned, skipped, stopEarly,
                tableList, succeededTables, convResults);
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrlNoDB(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "`");
            st.execute("USE `" + dbName + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }
}
```

Save to `src/main/java/com/tool/pipeline/steps/SchemaMigrateStep.java`.

- [ ] **Step 2: Create `VerifyStep.java`**

```java
package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.verifier.VerifyResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-connects to both databases and runs schema verification,
 * then prints the unified VERIFY SUMMARY table.
 *
 * Reads from context:
 *   "dbSummaries" (List<SummaryPrinter.DbSummary>)
 *   "dryRun"      (Boolean) — skips verify entirely on dry-run
 */
public class VerifyStep implements MigrationStep {

    private final AppConfig config;
    private final SchemaVerifier verifier;
    private final StructuredLogger log;
    private final SummaryPrinter printer;

    public VerifyStep(AppConfig config, SchemaVerifier verifier,
                      StructuredLogger log, SummaryPrinter printer) {
        this.config   = config;
        this.verifier = verifier;
        this.log      = log;
        this.printer  = printer;
    }

    @Override
    public String name() { return "Verify"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        boolean dryRun = Boolean.TRUE.equals(ctx.get("dryRun", Boolean.class));
        if (dryRun) return StepResult.ok("skipped in dry-run mode");

        List<SummaryPrinter.DbSummary> summaries = ctx.get("dbSummaries", List.class);
        if (summaries == null || summaries.isEmpty()) {
            return StepResult.ok("no databases to verify");
        }

        List<SummaryPrinter.IssueRow> verifyIssues = new ArrayList<>();

        for (SummaryPrinter.DbSummary db : summaries) {
            // Reconstruct succeededTables as String[][] from convResults (OK/WARN only)
            List<String[]> succeededTables = db.convResults().entrySet().stream()
                    .filter(e -> {
                        var s = e.getValue().getStatus();
                        return s == com.tool.common.model.ConversionResult.Status.OK
                            || s == com.tool.common.model.ConversionResult.Status.WARN;
                    })
                    .map(e -> e.getKey().split("\\.", 2))  // "schema.table" → ["schema","table"]
                    .toList();

            try (Connection ssConn = DriverManager.getConnection(
                         config.getSource().sqlServerJdbcUrlForDB(db.dbName()),
                         config.getSource().getUsername(),
                         config.getSource().getPassword());
                 Connection tidbConn = openTiDB(db.dbName())) {

                List<VerifyResult> results = verifier.verifyAll(ssConn, tidbConn, succeededTables);
                verifyIssues.addAll(SummaryPrinter.collectVerifyRows(db.dbName(), results));

            } catch (Exception e) {
                log.log("ERROR", "VERIFY failed", "db", db.dbName(), "error", e.getMessage());
                System.out.println("[ERROR] VERIFY failed for " + db.dbName() + ": " + e.getMessage());
            }
        }

        printer.print(summaries, verifyIssues);
        return StepResult.ok("verify complete");
    }

    private Connection openTiDB(String dbName) throws Exception {
        Connection conn = DriverManager.getConnection(
                config.getTarget().tidbJdbcUrlNoDB(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword());
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("USE `" + dbName + "`");
        }
        conn.setCatalog(dbName);
        return conn;
    }
}
```

Save to `src/main/java/com/tool/pipeline/steps/VerifyStep.java`.

- [ ] **Step 3: Compile**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/pipeline/steps/SchemaMigrateStep.java \
        src/main/java/com/tool/pipeline/steps/VerifyStep.java
git commit -m "feat: add SchemaMigrateStep and VerifyStep"
```

---

## Task 9: Slim `App.java` to ~50 lines

Now wire everything together in `App.java`. The old 507-line body is completely replaced. `App.java` only: parses CLI args, builds `StructuredLogger`, `ProgressReporter`, `SummaryPrinter`, and the three steps, then calls `pipeline.run()`.

**Files:**
- Modify: `src/main/java/com/tool/App.java` (full replacement)

- [ ] **Step 1: Replace `App.java`**

Replace the entire content of `src/main/java/com/tool/App.java` with:

```java
package com.tool;

import com.tool.config.AppConfig;
import com.tool.logging.StructuredLogger;
import com.tool.output.ProgressReporter;
import com.tool.output.SummaryPrinter;
import com.tool.pipeline.MigrationPipeline;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.pipeline.steps.PreCheckStep;
import com.tool.pipeline.steps.SchemaMigrateStep;
import com.tool.pipeline.steps.VerifyStep;
import com.tool.schema.converter.SchemaConverter;
import com.tool.schema.extractor.SqlServerExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import com.tool.schema.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SqlServerExtractor extractor;
    private final SchemaConverter converter;
    private final TiDBWriter writer;
    private final SchemaVerifier verifier;

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter,
               TiDBWriter writer, SchemaVerifier verifier) {
        this.config    = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer    = writer;
        this.verifier  = verifier;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = args.containsOption("dry-run");

        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(","))
                    .map(String::trim).collect(Collectors.toList());
        }

        // Banner
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│  ms2tidb  SQL Server → TiDB Migration   │");
        System.out.println("├──────────┬──────────────────────────────┤");
        System.out.printf( "│  source  │  %-28s│%n",
                config.getSource().getHost() + ":" + config.getSource().getPort());
        System.out.printf( "│  target  │  %-28s│%n",
                config.getTarget().getHost() + ":" + config.getTarget().getPort());
        if (dryRun)
            System.out.println("│  mode    │  dry-run                     │");
        System.out.println("└──────────┴──────────────────────────────┘");
        System.out.println();

        try (StructuredLogger log = StructuredLogger.open("ms2tidb.log")) {
            log.log("INFO", "ms2tidb started");

            ProgressReporter progress = new ProgressReporter();
            SummaryPrinter printer    = new SummaryPrinter();

            StepContext ctx = new StepContext();
            ctx.put("dryRun",          dryRun);
            ctx.put("tablesOverride",  tablesOverride);

            List<MigrationStep> steps = List.of(
                    new PreCheckStep(config),
                    new SchemaMigrateStep(config, extractor, converter, writer, log, progress),
                    new VerifyStep(config, verifier, log, printer)
            );

            StepResult result = new MigrationPipeline(steps).run(ctx);
            log.log("INFO", "ms2tidb finished", "fatal", result.isFatal());

            Integer totalFailed = ctx.get("totalFailed", Integer.class);
            if (totalFailed != null && totalFailed > 0) System.exit(1);
            if (result.isFatal()) System.exit(1);
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/sev7nyo/ms2tidb
mvn compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run full test suite**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/App.java
git commit -m "refactor: slim App.java to ~50 lines — wire pipeline"
```

---

## Task 10: Add `snapshot/` skeleton and final cleanup

- [ ] **Step 1: Add `snapshot/` placeholder**

```bash
mkdir -p /home/sev7nyo/ms2tidb/src/main/java/com/tool/snapshot/extractor
mkdir -p /home/sev7nyo/ms2tidb/src/main/java/com/tool/snapshot/writer
mkdir -p /home/sev7nyo/ms2tidb/src/main/java/com/tool/snapshot/verifier
touch /home/sev7nyo/ms2tidb/src/main/java/com/tool/snapshot/.gitkeep
```

- [ ] **Step 2: Run full test suite one final time**

```bash
cd /home/sev7nyo/ms2tidb
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 3: Build the jar**

```bash
cd /home/sev7nyo/ms2tidb
mvn package -DskipTests -q 2>&1 | tail -5
ls -lh target/*.jar
```

Expected: jar exists, `BUILD SUCCESS`.

- [ ] **Step 4: Final commit**

```bash
cd /home/sev7nyo/ms2tidb
git add src/main/java/com/tool/data/.gitkeep
git commit -m "chore: add snapshot/ domain skeleton for future snapshot data migration feature"
```

---

## Self-Review

**Spec coverage check:**

| Requirement | Task |
|---|---|
| `MigrationStep` interface | Task 1 |
| `StepContext` shared bus | Task 1 |
| `StepResult` outcome type | Task 1 |
| `MigrationPipeline` orchestrator | Task 2 |
| `StructuredLogger` extracted | Task 3 |
| `ProgressReporter` extracted | Task 4 |
| `SummaryPrinter` + `IssueRow` extracted | Task 4 |
| Models moved to `common/model/` | Task 5 |
| `schema/converter/`, `schema/extractor/`, `schema/writer/`, `schema/verifier/` | Task 5 |
| Test files updated to new packages | Task 6 |
| Old packages deleted | Task 6 |
| `PreCheckStep` | Task 7 |
| `SchemaMigrateStep` | Task 8 |
| `VerifyStep` | Task 8 |
| `App.java` slimmed to ~50 lines | Task 9 |
| `snapshot/` skeleton | Task 10 |

**Placeholder scan:** No TBDs, TODOs, or "similar to Task N" references. All code blocks contain complete implementations.

**Type consistency check:**
- `StepResult.ok()` / `StepResult.fatal()` / `StepResult.isFatal()` / `StepResult.message()` — consistent across Tasks 1, 2, 7, 8, 9.
- `StepContext.put()` / `StepContext.get(key, Class)` — consistent across Tasks 1, 7, 8, 9.
- `MigrationPipeline(List<MigrationStep>)` / `pipeline.run(ctx)` — consistent Tasks 2, 9.
- `SummaryPrinter.DbSummary(dbName, total, succeededCount, convResults)` — defined in Task 4, consumed in Tasks 8 and 9.
- `SummaryPrinter.collectVerifyRows(dbName, results)` — static method defined Task 4, called Task 8.
- `StructuredLogger.open(path)` / `log.log(level, msg, fields...)` — defined Task 3, used Tasks 8, 9.
