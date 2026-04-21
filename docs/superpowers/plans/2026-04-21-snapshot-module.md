# Snapshot Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `snapshot` command that uses Debezium Embedded Engine 3.5.0.Final to stream full data from SQL Server directly into TiDB, recording LSN for future CDC.

**Architecture:** Sequential per-database Debezium engine instances, each using `AsyncEmbeddedEngine` with `snapshot.mode=initial_only` and `Json` serialization format. `DebeziumEngine.create(Json.class)` yields `ChangeEvent<String, String>`. A plain-POJO `SnapshotSink` accumulates JSON batches, parses them via `SnapshotJsonParser` (Jackson), and routes `Map<String, Object>` rows to `TiDBBatchWriter` (multi-value INSERT). CDC prerequisites are checked by `CdcPreChecker` before engine startup.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Debezium 3.5.0.Final (embedded + SQL Server connector), Jackson Databind (transitive from Debezium), picocli 4.7.5, JUnit 5, Mockito

**API verification:** Task 0 probe test confirmed the actual Debezium 3.5 API. Key findings: `DebeziumEngine.create(Json.class)`, `ChangeEvent<String,String>.value()` is JSON String (not SourceRecord), `RecordCommitter` is inner interface `DebeziumEngine$RecordCommitter`, no `engine.await()` — use `engine.run()` via executor.

**Design spec:** `docs/superpowers/specs/2026-04-21-snapshot-module-design.md`

---

## File Map

### New files (all under `src/main/java/com/tool/snapshot/`)

| File | Responsibility |
|------|---------------|
| `SnapshotConfig.java` | Immutable config record for Debezium + snapshot params |
| `model/SnapshotTableResult.java` | Per-table result record |
| `model/SnapshotDbResult.java` | Per-DB result with LSN |
| `cdc/CdcPreChecker.java` | CDC prerequisite validation + auto-enable |
| `engine/DebeziumEngineFactory.java` | Builds AsyncEmbeddedEngine per DB |
| `sink/SnapshotJsonParser.java` | Jackson JSON parser for Debezium envelopes |
| `sink/SinkRecordConverter.java` | Map<String,Object> → JDBC PreparedStatement mapping |
| `sink/TiDBBatchWriter.java` | Multi-value INSERT buffering to TiDB |
| `sink/SnapshotSink.java` | Plain POJO, parses JSON events and routes to TiDBBatchWriter |
| `SnapshotStep.java` | MigrationStep orchestrator |

### New files (CLI)

| File | Responsibility |
|------|---------------|
| `src/main/java/com/tool/cli/SnapshotCommand.java` | picocli subcommand POJO |

### New test files (all under `src/test/java/com/tool/snapshot/`)

| File | Tests |
|------|-------|
| `SnapshotConfigTest.java` | Config construction and defaults |
| `model/SnapshotTableResultTest.java` | Record behavior |
| `model/SnapshotDbResultTest.java` | Record behavior |
| `cdc/CdcPreCheckerTest.java` | CDC checks and auto-enable |
| `sink/SnapshotJsonParserTest.java` | JSON envelope parsing |
| `sink/SinkRecordConverterTest.java` | Map<String,Object> → JDBC mapping |
| `sink/TiDBBatchWriterTest.java` | Buffering and flush logic |
| `sink/SnapshotSinkTest.java` | JSON parsing and routing |
| `SnapshotStepTest.java` | Orchestration integration (mocked engine) |

### Modified files

| File | Change |
|------|--------|
| `pom.xml` | Add 3 Debezium dependencies |
| `src/main/resources/application.yml` | Add `snapshot:` section |
| `src/main/java/com/tool/App.java` | Wire SnapshotCommand + runSnapshot() + help |

---

### Task 0: Verify Debezium 3.5 API

**Why:** The Debezium 3.5 Embedded Engine API has changed significantly from 3.4. Several key types (`ChangeConsumer`, `RecordCommitter`, `DebeziumEngine.create()`, `AsyncEmbeddedEngine` lifecycle) need to be verified against the actual JARs before writing implementation code. This task prevents compile-time surprises in Tasks 8-10.

**Files:**
- Create: `src/test/java/com/tool/snapshot/DebeziumApiProbeTest.java`

- [ ] **Step 1: Add Maven dependencies first (same as Task 1 Step 1)**

After the `picocli` dependency block, add:

```xml
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-api</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-embedded</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-connector-sqlserver</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
```

- [ ] **Step 2: Write API probe test**

Create `src/test/java/com/tool/snapshot/DebeziumApiProbeTest.java`:

```java
package com.tool.snapshot;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordCommitter;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Probe test: verifies Debezium 3.5 API signatures are what we expect.
 * Run this FIRST before implementing Tasks 8-10.
 * If any assertion fails, update the affected tasks to match the actual API.
 */
class DebeziumApiProbeTest {

    @Test
    void changeEvent_hasKeyAndValue() {
        // ChangeEvent<K, V> — verify generic structure
        var constructor = ChangeEvent.class.getDeclaredConstructors();
        assertTrue(constructor.length > 0, "ChangeEvent should have constructors");
    }

    @Test
    void recordCommitter_hasMarkProcessed() throws Exception {
        // RecordCommitter<R> should have markProcessed(R) and markBatchFinished()
        assertTrue(RecordCommitter.class.getDeclaredMethod("markProcessed", Object.class) != null,
                "RecordCommitter.markProcessed(Object) should exist");
        assertTrue(RecordCommitter.class.getDeclaredMethod("markBatchFinished") != null,
                "RecordCommitter.markBatchFinished() should exist");
    }

    @Test
    void debeziumEngine_hasCreateMethod() {
        // DebeziumEngine.create() — check the builder exists
        var methods = DebeziumEngine.class.getDeclaredMethods();
        boolean hasCreate = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().equals("create"));
        assertTrue(hasCreate, "DebeziumEngine.create() should exist");
    }

    @Test
    void sqlServerConnector_isLoadable() {
        // Verify SQL Server connector SPI is on classpath
        var connectorClass = "io.debezium.connector.sqlserver.SqlServerConnector";
        assertDoesNotThrow(() -> Class.forName(connectorClass),
                "SqlServerConnector should be on classpath");
    }

    @Test
    void sourceRecord_isOnClasspath() {
        assertDoesNotThrow(() -> SourceRecord.class.getDeclaredConstructor(
                java.util.Map.class, java.util.Map.class, String.class,
                org.apache.kafka.connect.data.Schema.class, Object.class,
                org.apache.kafka.connect.data.Schema.class, Object.class),
                "SourceRecord constructor should match expected signature");
    }
}
```

- [ ] **Step 3: Run probe test**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.DebeziumApiProbeTest -q 2>&1`
Expected: All 5 tests pass. If any fail, the Debezium API differs from expectations — note the failure and adjust Tasks 8-10 accordingly before proceeding.

- [ ] **Step 4: Record actual API signatures**

Run: `mvn dependency:resolve -q 2>&1 | head -5` to confirm deps resolved.

Then check key types:
```bash
javap -public -cp "$(mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" io.debezium.engine.DebeziumEngine 2>/dev/null | grep -E "create|build|await|run"
javap -public -cp "$(mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" io.debezium.engine.RecordCommitter 2>/dev/null | grep -E "mark|built"
```

Record the output — it determines the exact API for Tasks 8-10.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test/java/com/tool/snapshot/DebeziumApiProbeTest.java
git commit -m "chore(snapshot): add Debezium 3.5 API probe test and dependencies"
```

---

### Task 1: Add JaCoCo Exclusion (for snapshot)

**Files:**
- Modify: `pom.xml`

Note: Maven dependencies are already added in Task 0. This task only adds JaCoCo exclusion.

- [ ] **Step 1: Add snapshot module to JaCoCo excludes**

In `pom.xml`, inside the `jacoco-maven-plugin` `check` execution's `<excludes>` block, add:

```xml
                                <exclude>com/tool/snapshot/*.class</exclude>
                                <exclude>com/tool/snapshot/**/*.class</exclude>
```

Remove in Task 13 once tests are comprehensive.

- [ ] **Step 2: Commit**

```bash
git add pom.xml
git commit -m "chore: exclude snapshot from JaCoCo coverage threshold"
```

**Files:**
- Modify: `pom.xml:59` (after picocli dependency)

- [ ] **Step 1: Add Debezium dependencies**

After the `picocli` dependency block (line 58), add:

```xml
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-api</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-embedded</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-connector-sqlserver</artifactId>
            <version>3.5.0.Final</version>
        </dependency>
```

- [ ] **Step 2: Add snapshot module to JaCoCo excludes**

In `pom.xml`, inside the `jacoco-maven-plugin` `check` execution's `<excludes>` block, add:

```xml
                                <exclude>com/tool/snapshot/*.class</exclude>
                                <exclude>com/tool/snapshot/**/*.class</exclude>
```

This keeps snapshot code from dragging down coverage thresholds during initial development. Remove in Task 13.

- [ ] **Step 3: Verify build compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "deps: add Debezium 3.5.0.Final (embedded + sqlserver connector)"
```

---

### Task 2: SnapshotConfig + application.yml

**Files:**
- Create: `src/main/java/com/tool/snapshot/SnapshotConfig.java`
- Create: `src/test/java/com/tool/snapshot/SnapshotConfigTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/tool/snapshot/SnapshotConfigTest.java`:

```java
package com.tool.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotConfigTest {

    @Test
    void defaults_matchSpec() {
        SnapshotConfig cfg = SnapshotConfig.defaults();
        assertEquals("snapshot-offsets", cfg.offsetStoragePath());
        assertEquals("snapshot-schema-history", cfg.schemaHistoryPath());
        assertEquals("initial_only", cfg.snapshotMode());
        assertEquals(5000, cfg.batchInsertSize());
        assertEquals(2000, cfg.snapshotFetchSize());
        assertEquals(8192, cfg.maxQueueSize());
        assertEquals(500, cfg.pollIntervalMs());
        assertEquals(10000, cfg.offsetCommitIntervalMs());
        assertEquals(1, cfg.snapshotMaxThreads());
        assertEquals(1.0, cfg.snapshotMaxThreadsMultiplier());
    }

    @Test
    void builder_overridesDefaults() {
        SnapshotConfig cfg = SnapshotConfig.builder()
                .batchInsertSize(1000)
                .snapshotMaxThreads(4)
                .build();
        assertEquals(1000, cfg.batchInsertSize());
        assertEquals(4, cfg.snapshotMaxThreads());
        // Others keep defaults
        assertEquals("initial_only", cfg.snapshotMode());
    }

    @Test
    void withBatchSize_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withBatchInsertSize(999);
        assertNotSame(original, modified);
        assertEquals(999, modified.batchInsertSize());
        assertEquals(5000, original.batchInsertSize());
    }

    @Test
    void withFetchSize_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withSnapshotFetchSize(5000);
        assertEquals(5000, modified.snapshotFetchSize());
        assertEquals(2000, original.snapshotFetchSize());
    }

    @Test
    void withSnapshotThreads_returnsNewInstance() {
        SnapshotConfig original = SnapshotConfig.defaults();
        SnapshotConfig modified = original.withSnapshotMaxThreads(8);
        assertEquals(8, modified.snapshotMaxThreads());
        assertEquals(1, original.snapshotMaxThreads());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.SnapshotConfigTest -q 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Write SnapshotConfig implementation**

Create `src/main/java/com/tool/snapshot/SnapshotConfig.java`:

```java
package com.tool.snapshot;

import java.util.List;

public record SnapshotConfig(
        String offsetStoragePath,
        String schemaHistoryPath,
        String snapshotMode,
        int batchInsertSize,
        int snapshotFetchSize,
        int maxQueueSize,
        int pollIntervalMs,
        int offsetCommitIntervalMs,
        int snapshotMaxThreads,
        double snapshotMaxThreadsMultiplier
) {

    public static SnapshotConfig defaults() {
        return new SnapshotConfig(
                "snapshot-offsets",
                "snapshot-schema-history",
                "initial_only",
                5000,
                2000,
                8192,
                500,
                10000,
                1,
                1.0
        );
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public SnapshotConfig withBatchInsertSize(int batchInsertSize) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSnapshotFetchSize(int snapshotFetchSize) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    public SnapshotConfig withSnapshotMaxThreads(int snapshotMaxThreads) {
        return new SnapshotConfig(offsetStoragePath, schemaHistoryPath, snapshotMode,
                batchInsertSize, snapshotFetchSize, maxQueueSize, pollIntervalMs,
                offsetCommitIntervalMs, snapshotMaxThreads, snapshotMaxThreadsMultiplier);
    }

    /**
     * Build the Debezium table.include.list regex from table names.
     * If tables is null/empty, returns "dbo\\..*" (all dbo tables).
     */
    public String buildTableIncludeList(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return "dbo\\..*";
        }
        return tables.stream()
                .map(t -> "dbo\\." + t)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static class Builder {
        private String offsetStoragePath = "snapshot-offsets";
        private String schemaHistoryPath = "snapshot-schema-history";
        private String snapshotMode = "initial_only";
        private int batchInsertSize = 5000;
        private int snapshotFetchSize = 2000;
        private int maxQueueSize = 8192;
        private int pollIntervalMs = 500;
        private int offsetCommitIntervalMs = 10000;
        private int snapshotMaxThreads = 1;
        private double snapshotMaxThreadsMultiplier = 1.0;

        Builder(SnapshotConfig defaults) {
            this.offsetStoragePath = defaults.offsetStoragePath;
            this.schemaHistoryPath = defaults.schemaHistoryPath;
            this.snapshotMode = defaults.snapshotMode;
            this.batchInsertSize = defaults.batchInsertSize;
            this.snapshotFetchSize = defaults.snapshotFetchSize;
            this.maxQueueSize = defaults.maxQueueSize;
            this.pollIntervalMs = defaults.pollIntervalMs;
            this.offsetCommitIntervalMs = defaults.offsetCommitIntervalMs;
            this.snapshotMaxThreads = defaults.snapshotMaxThreads;
            this.snapshotMaxThreadsMultiplier = defaults.snapshotMaxThreadsMultiplier;
        }

        public Builder offsetStoragePath(String v) { this.offsetStoragePath = v; return this; }
        public Builder schemaHistoryPath(String v) { this.schemaHistoryPath = v; return this; }
        public Builder snapshotMode(String v) { this.snapshotMode = v; return this; }
        public Builder batchInsertSize(int v) { this.batchInsertSize = v; return this; }
        public Builder snapshotFetchSize(int v) { this.snapshotFetchSize = v; return this; }
        public Builder maxQueueSize(int v) { this.maxQueueSize = v; return this; }
        public Builder pollIntervalMs(int v) { this.pollIntervalMs = v; return this; }
        public Builder offsetCommitIntervalMs(int v) { this.offsetCommitIntervalMs = v; return this; }
        public Builder snapshotMaxThreads(int v) { this.snapshotMaxThreads = v; return this; }
        public Builder snapshotMaxThreadsMultiplier(double v) { this.snapshotMaxThreadsMultiplier = v; return this; }

        public SnapshotConfig build() {
            return new SnapshotConfig(
                    offsetStoragePath, schemaHistoryPath, snapshotMode,
                    batchInsertSize, snapshotFetchSize, maxQueueSize,
                    pollIntervalMs, offsetCommitIntervalMs,
                    snapshotMaxThreads, snapshotMaxThreadsMultiplier);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.SnapshotConfigTest -q`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 5: Add snapshot section to application.yml**

Append to `src/main/resources/application.yml`:

```yaml

snapshot:
  offset-storage-path: snapshot-offsets
  schema-history-path: snapshot-schema-history
  batch-insert-size: 5000
  snapshot-fetch-size: 2000
  max-queue-size: 8192
  poll-interval-ms: 500
  offset-commit-interval-ms: 10000
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tool/snapshot/SnapshotConfig.java src/test/java/com/tool/snapshot/SnapshotConfigTest.java src/main/resources/application.yml
git commit -m "feat(snapshot): add SnapshotConfig with defaults and builder"
```

---

### Task 3: SnapshotTableResult + SnapshotDbResult Models

**Files:**
- Create: `src/main/java/com/tool/snapshot/model/SnapshotTableResult.java`
- Create: `src/main/java/com/tool/snapshot/model/SnapshotDbResult.java`
- Create: `src/test/java/com/tool/snapshot/model/SnapshotTableResultTest.java`
- Create: `src/test/java/com/tool/snapshot/model/SnapshotDbResultTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tool/snapshot/model/SnapshotTableResultTest.java`:

```java
package com.tool.snapshot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTableResultTest {

    @Test
    void successResult_isNotError() {
        SnapshotTableResult r = new SnapshotTableResult("db1", "dbo", "Users", 100, 500L, null);
        assertFalse(r.isError());
        assertEquals("db1", r.dbName());
        assertEquals("dbo.Users", r.schema() + "." + r.table());
    }

    @Test
    void errorResult_isError() {
        SnapshotTableResult r = new SnapshotTableResult("db1", "dbo", "Bad", 0, 0L, "table not found");
        assertTrue(r.isError());
        assertEquals("table not found", r.error());
    }
}
```

Create `src/test/java/com/tool/snapshot/model/SnapshotDbResultTest.java`:

```java
package com.tool.snapshot.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDbResultTest {

    @Test
    void successResult_isNotError() {
        SnapshotDbResult r = new SnapshotDbResult("db1", "00000025:00000338:0003",
                "00000025:00000338:0002", 3, 500L, Instant.now(), null);
        assertFalse(r.isError());
        assertEquals(3, r.tables());
        assertEquals(500L, r.rows());
    }

    @Test
    void errorResult_isError() {
        SnapshotDbResult r = new SnapshotDbResult("db1", null, null, 0, 0L, Instant.now(), "CDC not enabled");
        assertTrue(r.isError());
        assertEquals("CDC not enabled", r.error());
    }

    @Test
    void isError_trueBlocksPipeline() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", null, null, 0, 0L, Instant.now(), "fail"))));
    }

    @Test
    void isError_falseNoBlock() {
        assertFalse(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 1, 10L, Instant.now(), null))));
    }

    @Test
    void isError_mixedBlocks() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 1, 10L, Instant.now(), null),
                new SnapshotDbResult("db2", null, null, 0, 0L, Instant.now(), "fail"))));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest="com.tool.snapshot.model.*Test" -q 2>&1 | tail -5`
Expected: FAIL — classes not found

- [ ] **Step 3: Write model implementations**

Create `src/main/java/com/tool/snapshot/model/SnapshotTableResult.java`:

```java
package com.tool.snapshot.model;

public record SnapshotTableResult(
        String dbName,
        String schema,
        String table,
        long rows,
        long elapsedMs,
        String error
) {
    public boolean isError() { return error != null; }
}
```

Create `src/main/java/com/tool/snapshot/model/SnapshotDbResult.java`:

```java
package com.tool.snapshot.model;

import java.time.Instant;
import java.util.List;

public record SnapshotDbResult(
        String dbName,
        String commitLsn,
        String changeLsn,
        int tables,
        long rows,
        Instant completedAt,
        String error
) {
    public boolean isError() { return error != null; }

    public static boolean shouldBlockPipeline(List<SnapshotDbResult> results) {
        return results.stream().anyMatch(SnapshotDbResult::isError);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest="com.tool.snapshot.model.*Test" -q`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/snapshot/model/ src/test/java/com/tool/snapshot/model/
git commit -m "feat(snapshot): add SnapshotTableResult and SnapshotDbResult models"
```

---

### Task 4: SnapshotCommand CLI Subcommand

**Files:**
- Create: `src/main/java/com/tool/cli/SnapshotCommand.java`

- [ ] **Step 1: Write SnapshotCommand**

Create `src/main/java/com/tool/cli/SnapshotCommand.java`:

```java
package com.tool.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Command(name = "snapshot", mixinStandardHelpOptions = true,
        description = "Export data directly to TiDB via Debezium CDC")
public class SnapshotCommand implements Runnable {

    @Option(names = "--tables", split = ",", paramLabel = "TABLE",
            description = "Only process specified tables (default: all)")
    public List<String> tables;

    @Option(names = "--dbs", split = ",", paramLabel = "DB",
            description = "Only process specified databases (default: all)")
    public List<String> databases;

    @Option(names = "--batch-size", paramLabel = "N", defaultValue = "5000",
            description = "INSERT batch size (default: 5000)")
    public int batchSize;

    @Option(names = "--fetch-size", paramLabel = "N", defaultValue = "2000",
            description = "Debezium snapshot fetch size (default: 2000)")
    public int fetchSize;

    @Option(names = "--snapshot-threads", paramLabel = "N", defaultValue = "1",
            description = "Debezium parallel chunk threads (default: 1)")
    public int snapshotThreads;

    @Option(names = "--auto-enable-cdc",
            description = "Automatically enable CDC on SQL Server")
    public boolean autoEnableCdc;

    @Override
    public void run() {}
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/cli/SnapshotCommand.java
git commit -m "feat(snapshot): add SnapshotCommand picocli subcommand"
```

---

### Task 5: CdcPreChecker

**Files:**
- Create: `src/main/java/com/tool/snapshot/cdc/CdcPreChecker.java`
- Create: `src/test/java/com/tool/snapshot/cdc/CdcPreCheckerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tool/snapshot/cdc/CdcPreCheckerTest.java`:

```java
package com.tool.snapshot.cdc;

import com.tool.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CdcPreCheckerTest {

    private AppConfig.DbConfig sourceConfig;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        sourceConfig = new AppConfig.DbConfig();
        sourceConfig.setHost("127.0.0.1");
        sourceConfig.setPort(1433);
        sourceConfig.setUsername("sa");
        sourceConfig.setPassword("pw");
        conn = mock(Connection.class);
    }

    @Test
    void agentRunning_returnsTrue() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertTrue(checker.isAgentRunning(conn));
    }

    @Test
    void agentNotRunning_returnsFalse() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertFalse(checker.isAgentRunning(conn));
    }

    @Test
    void isCdcEnabled_returnsTrue() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(1);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertTrue(checker.isCdcEnabled(conn, "testdb"));
    }

    @Test
    void isCdcEnabled_returnsFalse() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        assertFalse(checker.isCdcEnabled(conn, "testdb"));
    }

    @Test
    void getTablesWithoutCdc_returnsEmptyWhenAllEnabled() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("Users", "Orders");
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        List<String[]> tables = List.of(new String[]{"dbo", "Users"}, new String[]{"dbo", "Orders"});
        List<String> missing = checker.getTablesWithoutCdc(conn, "testdb", tables);
        assertTrue(missing.isEmpty());
    }

    @Test
    void getTablesWithoutCdc_returnsMissingTables() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Users");
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        List<String[]> tables = List.of(new String[]{"dbo", "Users"}, new String[]{"dbo", "Orders"});
        List<String> missing = checker.getTablesWithoutCdc(conn, "testdb", tables);
        assertEquals(1, missing.size());
        assertEquals("Orders", missing.get(0));
    }

    @Test
    void checkResult_hasErrorWhenAgentNotRunning() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        CdcPreChecker checker = new CdcPreChecker(sourceConfig);
        CdcPreChecker.CdcCheckResult result = checker.check(conn, "testdb",
                List.of(new String[]{"dbo", "Users"}), false);

        assertTrue(result.hasError());
        assertNotNull(result.errorMessage());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.cdc.CdcPreCheckerTest -q 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Write CdcPreChecker implementation**

Create `src/main/java/com/tool/snapshot/cdc/CdcPreChecker.java`:

```java
package com.tool.snapshot.cdc;

import com.tool.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CdcPreChecker {

    private static final Logger log = LoggerFactory.getLogger(CdcPreChecker.class);

    private final AppConfig.DbConfig source;

    public CdcPreChecker(AppConfig.DbConfig source) {
        this.source = source;
    }

    public record CdcCheckResult(
            boolean hasError,
            String errorMessage,
            boolean agentRunning,
            boolean cdcEnabled,
            List<String> tablesWithoutCdc
    ) {}

    public boolean isAgentRunning(Connection conn) throws Exception {
        String sql = "SELECT 1 FROM sys.dm_exec_sessions WHERE program_name LIKE 'SQLAgent%' AND status = 'running'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    public boolean isCdcEnabled(Connection conn, String dbName) throws Exception {
        String sql = "SELECT is_cdc_enabled FROM sys.databases WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    /**
     * Returns table names from `tables` that do NOT have CDC capture instances.
     */
    public List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                             List<String[]> tables) throws Exception {
        String sql = "SELECT t.name FROM cdc.change_tables ct " +
                "JOIN sys.tables t ON ct.source_object_id = t.object_id " +
                "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                "WHERE s.name = ? AND t.name IN (" +
                tables.stream().map(t -> "?").collect(java.util.stream.Collectors.joining(",")) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "dbo");
            for (int i = 0; i < tables.size(); i++) {
                ps.setString(i + 2, tables.get(i)[1]);
            }
            Set<String> enabled = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    enabled.add(rs.getString(1));
                }
            }
            List<String> missing = new ArrayList<>();
            for (String[] t : tables) {
                if (!enabled.contains(t[1])) {
                    missing.add(t[1]);
                }
            }
            return missing;
        }
    }

    public void enableCdc(Connection conn, String dbName) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_db");
            log.info("[\"cdc enabled\"] [database={}]", dbName);
        }
    }

    public void enableCdcForTable(Connection conn, String dbName, String schema, String table) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("EXEC sys.sp_cdc_enable_table @source_schema = '" + schema
                    + "', @source_name = '" + table
                    + "', @role_name = NULL");
            log.info("[\"cdc enabled\"] [database={}] [table={}.{}]", dbName, schema, table);
        }
    }

    /**
     * Full check: agent, db CDC, table CDC. Optionally auto-enable.
     * Returns result with error details if any check fails.
     */
    public CdcCheckResult check(Connection conn, String dbName,
                                List<String[]> tables, boolean autoEnable) {
        try {
            boolean agentRunning = isAgentRunning(conn);
            if (!agentRunning) {
                return new CdcCheckResult(true,
                        "SQL Server Agent is not running. Start it before using snapshot.", false, false, tables.stream().map(t -> t[1]).toList());
            }

            boolean cdcEnabled = isCdcEnabled(conn, dbName);
            if (!cdcEnabled) {
                if (autoEnable) {
                    enableCdc(conn, dbName);
                    cdcEnabled = true;
                } else {
                    return new CdcCheckResult(true,
                            "CDC is not enabled on database '" + dbName + "'. Use --auto-enable-cdc or run: EXEC sys.sp_cdc_enable_db",
                            true, false, tables.stream().map(t -> t[1]).toList());
                }
            }

            List<String> missing = getTablesWithoutCdc(conn, dbName, tables);
            if (!missing.isEmpty()) {
                if (autoEnable) {
                    for (String[] t : tables) {
                        if (missing.contains(t[1])) {
                            enableCdcForTable(conn, dbName, t[0], t[1]);
                        }
                    }
                    missing = List.of();
                } else {
                    return new CdcCheckResult(true,
                            "CDC not enabled for tables: " + missing + ". Use --auto-enable-cdc or run: EXEC sys.sp_cdc_enable_table",
                            true, true, missing);
                }
            }

            return new CdcCheckResult(false, null, true, true, List.of());
        } catch (Exception e) {
            return new CdcCheckResult(true, "CDC check failed: " + e.getMessage(), false, false,
                    tables.stream().map(t -> t[1]).toList());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.cdc.CdcPreCheckerTest -q`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/snapshot/cdc/ src/test/java/com/tool/snapshot/cdc/
git commit -m "feat(snapshot): add CdcPreChecker for CDC prerequisite validation"
```

---

### Task 6: SinkRecordConverter

**Files:**
- Create: `src/main/java/com/tool/snapshot/sink/SinkRecordConverter.java`
- Create: `src/test/java/com/tool/snapshot/sink/SinkRecordConverterTest.java`

**Design:** Maps `Map<String, Object>` (Jackson-parsed JSON values) to JDBC `PreparedStatement` methods. No Kafka Connect Schema/Struct dependency.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tool/snapshot/sink/SinkRecordConverterTest.java`:

```java
package com.tool.snapshot.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SinkRecordConverterTest {

    private SinkRecordConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SinkRecordConverter();
    }

    @Test
    void integer_setInt() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "age", 25);
        verify(ps).setInt(1, 25);
    }

    @Test
    void long_setLong() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "id", 9999999999L);
        verify(ps).setLong(1, 9999999999L);
    }

    @Test
    void double_setDouble() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "price", 19.99);
        verify(ps).setDouble(1, 19.99);
    }

    @Test
    string_setString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "name", "Alice");
        verify(ps).setString(1, "Alice");
    }

    @Test
    void boolean_setBoolean() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "active", true);
        verify(ps).setBoolean(1, true);
    }

    @Test
    void bigDecimal_setBigDecimal() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "amount", new BigDecimal("99.99"));
        verify(ps).setBigDecimal(1, new BigDecimal("99.99"));
    }

    @Test
    void multipleFields_correctIndex() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> row = Map.of("id", 1, "name", "Bob");
        converter.bind(ps, row, List.of("id", "name"));
        verify(ps).setInt(1, 1);
        verify(ps).setString(2, "Bob");
    }

    @Test
    nullValue_setNull() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "name", null);
        verify(ps).setNull(1, Types.VARCHAR);
    }

    @Test
    byteArray_setBytes() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        converter.bind(ps, "data", new byte[]{1, 2, 3});
        verify(ps).setBytes(1, new byte[]{1, 2, 3});
    }

    @Test
    unsupportedType_throws() {
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(IllegalArgumentException.class,
                () -> converter.bind(ps, "bad", new Object()));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.sink.SinkRecordConverterTest -q -Djacoco.skip=true 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Write SinkRecordConverter implementation**

Create `src/main/java/com/tool/snapshot/sink/SinkRecordConverter.java`:

```java
package com.tool.snapshot.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Maps Jackson-parsed Map<String, Object> values to JDBC PreparedStatement parameters.
 * No Kafka Connect Schema/Struct dependency — Debezium Json format + Jackson
 * handles all type resolution.
 */
public class SinkRecordConverter {

    private static final Logger log = LoggerFactory.getLogger(SinkRecordConverter.class);

    /**
     * Bind a single value to PreparedStatement at parameter index 1.
     */
    public void bind(PreparedStatement ps, String fieldName, Object value) throws SQLException {
        bindAt(ps, 1, value);
    }

    /**
     * Bind all entries from a row Map to PreparedStatement.
     * Fields are bound in the order of `fieldNames` (1-indexed).
     */
    public void bind(PreparedStatement ps, Map<String, Object> row, List<String> fieldNames) throws SQLException {
        for (int i = 0; i < fieldNames.size(); i++) {
            Object val = row.get(fieldNames.get(i));
            bindAt(ps, i + 1, val);
        }
    }

    private void bindAt(PreparedStatement ps, int idx, Object val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.VARCHAR);
            return;
        }
        if (val instanceof Integer) {
            ps.setInt(idx, (Integer) val);
        } else if (val instanceof Long) {
            ps.setLong(idx, (Long) val);
        } else if (val instanceof Double) {
            ps.setDouble(idx, (Double) val);
        } else if (val instanceof Float) {
            ps.setDouble(idx, ((Float) val).doubleValue());
        } else if (val instanceof BigDecimal) {
            ps.setBigDecimal(idx, (BigDecimal) val);
        } else if (val instanceof Boolean) {
            ps.setBoolean(idx, (Boolean) val);
        } else if (val instanceof byte[]) {
            ps.setBytes(idx, (byte[]) val);
        } else if (val instanceof String) {
            // Heuristic: try parsing as timestamp, fallback to string
            ps.setString(idx, val.toString());
        } else {
            ps.setString(idx, val.toString());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.sink.SinkRecordConverterTest -q -Djacoco.skip=true`
Expected: Tests run: 10, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/snapshot/sink/SinkRecordConverter.java src/test/java/com/tool/snapshot/sink/SinkRecordConverterTest.java
git commit -m "feat(snapshot): add SinkRecordConverter for Map to JDBC binding"
```

---

### Task 7: TiDBBatchWriter

**Files:**
- Create: `src/main/java/com/tool/snapshot/sink/TiDBBatchWriter.java`
- Create: `src/test/java/com/tool/snapshot/sink/TiDBBatchWriterTest.java`

**Design:** Buffers `Map<String, Object>` rows (Jackson-parsed from Debezium JSON) per table, flushes via multi-value INSERT to TiDB. No Kafka Connect dependency.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tool/snapshot/sink/TiDBBatchWriterTest.java`:

```java
package com.tool.snapshot.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiDBBatchWriterTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;
    private TiDBBatchWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        writer = new TiDBBatchWriter(dataSource, new SinkRecordConverter(), 3);
    }

    private Map<String, Object> makeRow(int id, String name) {
        return Map.of("id", id, "name", name);
    }

    @Test
    void accumulate_belowThreshold_noFlush() {
        writer.accumulate("testdb", "users", makeRow(1, "Alice"));
        verifyNoInteractions(ps);
    }

    @Test
    void accumulate_atThreshold_flushes() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "users", makeRow(3, "C"));
        verify(ps, times(3)).addBatch();
        verify(ps).executeBatch();
    }

    @Test
    void flushAll_flushesRemaining() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.flushAll();
        verify(ps).addBatch();
        verify(ps).executeBatch();
    }

    @Test
    void totalRows_tracksCorrectly() {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "orders", makeRow(3, "C"));
        assertEquals(3, writer.getTotalRows());
    }

    @Test
    void tableRows_tracksPerTable() {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "orders", makeRow(3, "C"));
        Map<String, Long> tableRows = writer.getTableRows();
        assertEquals(2L, tableRows.get("users"));
        assertEquals(1L, tableRows.get("orders"));
    }

    @Test
    void flushAll_resetsBuffers() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.flushAll();
        assertEquals(0, writer.getTotalRows());
        assertTrue(writer.getTableRows().isEmpty());
    }

    @Test
    void accumulate_afterFlush_reusesConnection() throws Exception {
        writer.accumulate("testdb", "users", makeRow(1, "A"));
        writer.accumulate("testdb", "users", makeRow(2, "B"));
        writer.accumulate("testdb", "users", makeRow(3, "C"));
        writer.accumulate("testdb", "users", makeRow(4, "D"));
        writer.accumulate("testdb", "users", makeRow(5, "E"));
        writer.accumulate("testdb", "users", makeRow(6, "F"));
        verify(ps, times(6)).addBatch();
        verify(ps, times(2)).executeBatch();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.sink.TiDBBatchWriterTest -q -Djacoco.skip=true 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Write TiDBBatchWriter implementation**

Create `src/main/java/com/tool/snapshot/sink/TiDBBatchWriter.java`:

```java
package com.tool.snapshot.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TiDBBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TiDBBatchWriter.class);

    private final DataSource dataSource;
    private final SinkRecordConverter converter;
    private final int batchSize;

    private final Map<String, List<Map<String, Object>>> buffer = new HashMap<>();
    private final Map<String, List<String>> fieldOrders = new HashMap<>();
    private final Map<String, String> tableDbNames = new HashMap<>();
    private long totalRows = 0L;
    private final Map<String, Long> tableRowCounts = new HashMap<>();

    public TiDBBatchWriter(DataSource dataSource, SinkRecordConverter converter, int batchSize) {
        this.dataSource = dataSource;
        this.converter = converter;
        this.batchSize = batchSize;
    }

    public void accumulate(String dbName, String table, Map<String, Object> after) {
        tableDbNames.putIfAbsent(table, dbName);
        buffer.computeIfAbsent(table, k -> new ArrayList<>()).add(after);
        if (!fieldOrders.containsKey(table)) {
            fieldOrders.put(table, new ArrayList<>(new LinkedHashMap<>(after).keySet()));
        }
        totalRows++;
        tableRowCounts.merge(table, 1L, Long::sum);

        if (buffer.get(table).size() >= batchSize) {
            try {
                flushTable(table);
            } catch (Exception e) {
                log.error("[\"flush failed\"] [table={}] [error=\"{}\"]", table, e.getMessage());
            }
        }
    }

    public void flushAll() throws SQLException {
        for (String table : new ArrayList<>(buffer.keySet())) {
            if (!buffer.get(table).isEmpty()) {
                flushTable(table);
            }
        }
        totalRows = 0;
        tableRowCounts.clear();
    }

    private void flushTable(String table) throws SQLException {
        List<Map<String, Object>> rows = buffer.get(table);
        if (rows == null || rows.isEmpty()) return;

        String dbName = tableDbNames.getOrDefault(table, "unknown");
        List<String> fields = fieldOrders.get(table);
        String cols = String.join(", ", fields);
        String placeholders = String.join(", ", fields.stream().map(f -> "?").toList());
        String sql = "INSERT INTO `" + dbName + "`.`" + table + "` (" + cols + ") VALUES (" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                converter.bind(ps, row, fields);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        rows.clear();
    }

    public long getTotalRows() { return totalRows; }

    public Map<String, Long> getTableRows() { return new HashMap<>(tableRowCounts); }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.sink.TiDBBatchWriterTest -q -Djacoco.skip=true`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/snapshot/sink/TiDBBatchWriter.java src/test/java/com/tool/snapshot/sink/TiDBBatchWriterTest.java
git commit -m "feat(snapshot): add TiDBBatchWriter with Map-based multi-value INSERT"
```

---

### Task 8: SnapshotJsonParser + SnapshotSink

**Files:**
- Create: `src/main/java/com/tool/snapshot/sink/SnapshotJsonParser.java`
- Create: `src/test/java/com/tool/snapshot/sink/SnapshotJsonParserTest.java`
- Create: `src/main/java/com/tool/snapshot/sink/SnapshotSink.java`
- Create: `src/test/java/com/tool/snapshot/sink/SnapshotSinkTest.java`

**Design:** SnapshotJsonParser wraps Jackson `ObjectMapper` to parse Debezium JSON event envelopes. SnapshotSink is a plain POJO that accepts `List<ChangeEvent<String, String>>` from Debezium's `.notifying()` callback, parses JSON via SnapshotJsonParser, and routes `Map<String, Object>` rows to TiDBBatchWriter.

- [ ] **Step 1: Write SnapshotJsonParser test**

Create `src/test/java/com/tool/snapshot/sink/SnapshotJsonParserTest.java`:

```java
package com.tool.snapshot.sink;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotJsonParserTest {

    private final SnapshotJsonParser parser = new SnapshotJsonParser();

    @Test
    void parse_snapshotRecord_extractsAllFields() {
        String json = """
        {
            "schema": {...},
            "payload": {
                "before": null,
                "after": {"id": 1, "name": "Alice", "age": 30},
                "source": {
                    "version": "3.5.0.Final",
                    "connector": "sqlserver",
                    "name": "any2tidb_testdb",
                    "db": "testdb",
                    "schema": "dbo",
                    "table": "users",
                    "commit_lsn": "00000025:00000338:0003",
                    "change_lsn": "00000025:00000338:0002",
                    "snapshot": "true"
                },
                "op": "r",
                "ts_ms": 1714000000000
            }
        }
        """;

        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertEquals("testdb", r.dbName());
        assertEquals("dbo", r.schema());
        assertEquals("users", r.table());
        assertEquals(Map.of("id", 1, "name", "Alice", "age", 30), r.after());
        assertEquals("00000025:00000338:0003", r.commitLsn());
        assertEquals("00000025:00000338:0002", r.changeLsn());
        assertTrue(r.isSnapshot());
    }

    @Test
    void parse_nonSnapshotRecord_isNotSnapshot() {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "false"},
                "op": "c"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertFalse(r.isSnapshot());
    }

    @Test
    void parse_lastSnapshot_isSnapshot() {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "last"},
                "op": "r"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertTrue(r.isSnapshot());
    }

    @Test
    void parse_nullAfter_afterIsNull() {
        String json = """
        {
            "payload": {
                "after": null,
                "source": {"snapshot": "true"},
                "op": "d"
            }
        }
        """;
        SnapshotJsonParser.ParsedRecord r = parser.parse(json);
        assertNull(r.after());
    }
}
```

- [ ] **Step 2: Write SnapshotSink test**

Create `src/test/java/com/tool/snapshot/sink/SnapshotSinkTest.java`:

```java
package com.tool.snapshot.sink;

import io.debezium.engine.ChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SnapshotSinkTest {

    private TiDBBatchWriter batchWriter;
    private SnapshotSink sink;

    @BeforeEach
    void setUp() {
        batchWriter = mock(TiDBBatchWriter.class);
        sink = new SnapshotSink(batchWriter);
    }

    private String makeSnapshotJson(String dbName, String table, String afterJson) {
        return """
        {
            "payload": {
                "before": null,
                "after": %s,
                "source": {
                    "db": "%s",
                    "schema": "dbo",
                    "table": "%s",
                    "commit_lsn": "00000025:00000338:0003",
                    "change_lsn": "00000025:00000338:0002",
                    "snapshot": "true"
                },
                "op": "r"
            }
        }
        """.formatted(afterJson, dbName, table);
    }

    @Test
    void snapshotRecord_withAfterData_isRoutedToWriter() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1, \"name\": \"Alice\"}");
        ChangeEvent<String, String> event = new ChangeEvent<>("key", json);

        sink.accept(List.of(event));

        verify(batchWriter).accumulate(eq("testdb"), eq("users"), any(Map.class));
    }

    @Test
    void snapshotRecord_nullAfter_isSkipped() {
        String json = makeSnapshotJson("testdb", "users", "null");
        ChangeEvent<String, String> event = new ChangeEvent<>("key", json);

        sink.accept(List.of(event));

        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }

    @Test
    void nonSnapshotRecord_isSkipped() {
        String json = """
        {
            "payload": {
                "source": {"snapshot": "false"},
                "op": "c"
            }
        }
        """;
        ChangeEvent<String, String> event = new ChangeEvent<>("key", json);

        sink.accept(List.of(event));

        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }

    @Test
    void lsnExtracted_fromSnapshotRecord() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1}");
        ChangeEvent<String, String> event = new ChangeEvent<>("key", json);

        sink.accept(List.of(event));

        assertEquals("00000025:00000338:0003", sink.getCommitLsn());
        assertEquals("00000025:00000338:0002", sink.getChangeLsn());
    }

    @Test
    void reset_clearsLsn() {
        String json = makeSnapshotJson("testdb", "users", "{\"id\": 1}");
        ChangeEvent<String, String> event = new ChangeEvent<>("key", json);

        sink.accept(List.of(event));
        sink.reset();

        assertNull(sink.getCommitLsn());
        assertNull(sink.getChangeLsn());
    }

    @Test
    void recordConversionError_loggedAndSkipped() {
        ChangeEvent<String, String> event = new ChangeEvent<>("key", "not valid json");

        assertDoesNotThrow(() -> sink.accept(List.of(event)));
        verify(batchWriter, never()).accumulate(anyString(), anyString(), any());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest="com.tool.snapshot.sink.SnapshotJsonParserTest,com.tool.snapshot.sink.SnapshotSinkTest" -q -Djacoco.skip=true 2>&1 | tail -5`
Expected: FAIL — classes not found

- [ ] **Step 4: Write SnapshotJsonParser implementation**

Create `src/main/java/com/tool/snapshot/sink/SnapshotJsonParser.java`:

```java
package com.tool.snapshot.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class SnapshotJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public record ParsedRecord(
            String dbName, String schema, String table,
            Map<String, Object> before, Map<String, Object> after,
            String op, String commitLsn, String changeLsn, String snapshot
    ) {
        public boolean isSnapshot() { return "true".equals(snapshot) || "last".equals(snapshot); }
    }

    public ParsedRecord parse(String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        JsonNode source = payload.get("source");

        String dbName = source != null ? source.path("db").asText() : null;
        String schema = source != null ? source.path("schema").asText() : null;
        String table = source != null ? source.path("table").asText() : null;
        String commitLsn = source != null ? source.path("commit_lsn").asText(null) : null;
        String changeLsn = source != null ? source.path("change_lsn").asText(null) : null;
        String snapshot = source != null ? source.path("snapshot").asText(null) : null;
        String op = payload.path("op").asText(null);

        Map<String, Object> after = toMap(payload.get("after"));
        Map<String, Object> before = toMap(payload.get("before"));

        return new ParsedRecord(dbName, schema, table, before, after,
                op, commitLsn, changeLsn, snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return mapper.convertValue(node, LinkedHashMap.class);
    }
}
```

- [ ] **Step 5: Write SnapshotSink implementation**

Create `src/main/java/com/tool/snapshot/sink/SnapshotSink.java`:

```java
package com.tool.snapshot.sink;

import io.debezium.engine.ChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSink.class);

    private final TiDBBatchWriter batchWriter;
    private final SnapshotJsonParser parser = new SnapshotJsonParser();
    private String commitLsn;
    private String changeLsn;

    public SnapshotSink(TiDBBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public void accept(List<ChangeEvent<String, String>> events) {
        for (ChangeEvent<String, String> event : events) {
            try {
                String json = event.value();
                if (json == null) continue;
                SnapshotJsonParser.ParsedRecord record = parser.parse(json);
                if (!record.isSnapshot()) continue;
                if (record.after() == null) continue;
                batchWriter.accumulate(record.dbName(), record.table(), record.after());
                trackLsn(record);
            } catch (Exception e) {
                log.error("[\"record failed\"] [error=\"{}\"]", e.getMessage());
            }
        }
    }

    private void trackLsn(SnapshotJsonParser.ParsedRecord record) {
        if (record.commitLsn() != null) commitLsn = record.commitLsn();
        if (record.changeLsn() != null) changeLsn = record.changeLsn();
    }

    public String getCommitLsn() { return commitLsn; }
    public String getChangeLsn() { return changeLsn; }

    public void reset() {
        commitLsn = null;
        changeLsn = null;
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest="com.tool.snapshot.sink.SnapshotJsonParserTest,com.tool.snapshot.sink.SnapshotSinkTest" -q -Djacoco.skip=true`
Expected: Tests run: 10, Failures: 0

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tool/snapshot/sink/SnapshotJsonParser.java src/test/java/com/tool/snapshot/sink/SnapshotJsonParserTest.java src/main/java/com/tool/snapshot/sink/SnapshotSink.java src/test/java/com/tool/snapshot/sink/SnapshotSinkTest.java
git commit -m "feat(snapshot): add SnapshotJsonParser and SnapshotSink for JSON event processing"
```

---

### Task 9: DebeziumEngineFactory

**Files:**
- Create: `src/main/java/com/tool/snapshot/engine/DebeziumEngineFactory.java`

**API:** Uses verified Debezium 3.5 API: `DebeziumEngine.create(Json.class)` returns `Builder<ChangeEvent<String, String>>`. Uses `.notifying(Consumer<ChangeEvent<String, String>>)` for simple callback and `.using(CompletionCallback)` for error handling.

- [ ] **Step 1: Write DebeziumEngineFactory**

Create `src/main/java/com/tool/snapshot/engine/DebeziumEngineFactory.java`:

```java
package com.tool.snapshot.engine;

import com.tool.config.AppConfig;
import com.tool.snapshot.SnapshotConfig;
import com.tool.snapshot.sink.SnapshotSink;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class DebeziumEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineFactory.class);

    private final AppConfig.DbConfig source;

    public DebeziumEngineFactory(AppConfig.DbConfig source) {
        this.source = source;
    }

    public DebeziumEngine<ChangeEvent<String, String>> create(
            String dbName,
            SnapshotConfig snapshotConfig,
            java.util.List<String> tableFilter,
            SnapshotSink sink,
            Runnable onComplete) {

        Properties props = new Properties();
        props.setProperty("name", "any2tidb-snapshot-" + dbName);
        props.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
        props.setProperty("database.hostname", source.getHost());
        props.setProperty("database.port", String.valueOf(source.getPort()));
        props.setProperty("database.user", source.getUsername());
        props.setProperty("database.password", source.getPassword());
        props.setProperty("database.dbname", dbName);
        props.setProperty("database.server.name", "any2tidb_" + dbName);
        props.setProperty("database.history.file.filename",
                snapshotConfig.schemaHistoryPath() + "/" + dbName + ".history");
        props.setProperty("offset.storage.file.filename",
                snapshotConfig.offsetStoragePath() + "/" + dbName + ".offset");
        props.setProperty("snapshot.mode", snapshotConfig.snapshotMode());
        props.setProperty("snapshot.fetch.size", String.valueOf(snapshotConfig.snapshotFetchSize()));
        props.setProperty("max.queue.size", String.valueOf(snapshotConfig.maxQueueSize()));
        props.setProperty("poll.interval.ms", String.valueOf(snapshotConfig.pollIntervalMs()));
        props.setProperty("offset.flush.interval.ms", String.valueOf(snapshotConfig.offsetCommitIntervalMs()));
        props.setProperty("snapshot.max.threads", String.valueOf(snapshotConfig.snapshotMaxThreads()));
        props.setProperty("table.include.list", snapshotConfig.buildTableIncludeList(tableFilter));

        log.info("[\"creating debezium engine\"] [database={}] [snapshotMode={}] [tables={}]",
                dbName, snapshotConfig.snapshotMode(), snapshotConfig.buildTableIncludeList(tableFilter));

        return DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(sink::accept)
                .using((DebeziumEngine.CompletionCallback) (success, message, error) -> {
                    if (error != null) {
                        log.error("[\"engine failed\"] [database={}] [error=\"{}\"]", dbName, error.getMessage());
                    } else {
                        log.info("[\"engine completed\"] [database={}] [message=\"{}\"]", dbName, message);
                    }
                    if (onComplete != null) onComplete.run();
                })
                .build();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/snapshot/engine/DebeziumEngineFactory.java
git commit -m "feat(snapshot): add DebeziumEngineFactory using Json format"
```

---

### Task 10: SnapshotStep (Orchestrator)

**Files:**
- Create: `src/main/java/com/tool/snapshot/SnapshotStep.java`
- Create: `src/test/java/com/tool/snapshot/SnapshotStepTest.java`

**Design:** Implements MigrationStep. Flow: CDC pre-check → build Debezium engine per DB → run via executor + CountDownLatch → flush writer → collect results → write snapshot-meta.json.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/tool/snapshot/SnapshotStepTest.java`:

```java
package com.tool.snapshot;

import com.tool.config.AppConfig;
import com.tool.pipeline.StepContext;
import com.tool.snapshot.model.SnapshotDbResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStepTest {

    private AppConfig config;
    private StepContext ctx;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost("127.0.0.1"); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        config.setSource(src);

        AppConfig.DbConfig tgt = new AppConfig.DbConfig();
        tgt.setHost("127.0.0.1"); tgt.setPort(4000);
        tgt.setUsername("root"); tgt.setPassword("");
        config.setTarget(tgt);

        ctx = new StepContext();
        ctx.put("tables", List.of());
    }

    @Test
    void stepName_isSnapshot() {
        javax.sql.DataSource ds = mock(javax.sql.DataSource.class);
        SnapshotStep step = new SnapshotStep(config, ds);
        assertEquals("Snapshot", step.name());
    }

    @Test
    void snapshotConfig_withOverrides() {
        ctx.put("batchSize", 1000);
        ctx.put("fetchSize", 5000);
        ctx.put("snapshotThreads", 4);

        Integer batchSize = ctx.get("batchSize", Integer.class);
        Integer fetchSize = ctx.get("fetchSize", Integer.class);
        Integer threads = ctx.get("snapshotThreads", Integer.class);

        assertEquals(1000, batchSize);
        assertEquals(5000, fetchSize);
        assertEquals(4, threads);
    }

    @Test
    void snapshotDbResult_shouldBlockPipeline() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", null, null, 0, 0L,
                        java.time.Instant.now(), "CDC not enabled"))));
    }

    @Test
    void snapshotDbResult_noBlockOnSuccess() {
        assertFalse(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 5, 100L,
                        java.time.Instant.now(), null))));
    }
}
```

- [ ] **Step 2: Run tests to verify they compile**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.SnapshotStepTest -q -Djacoco.skip=true`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 3: Write SnapshotStep implementation**

Create `src/main/java/com/tool/snapshot/SnapshotStep.java`:

```java
package com.tool.snapshot;

import com.tool.config.AppConfig;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.snapshot.cdc.CdcPreChecker;
import com.tool.snapshot.engine.DebeziumEngineFactory;
import com.tool.snapshot.model.SnapshotDbResult;
import com.tool.snapshot.sink.SinkRecordConverter;
import com.tool.snapshot.sink.SnapshotSink;
import com.tool.snapshot.sink.TiDBBatchWriter;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SnapshotStep implements MigrationStep {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStep.class);
    private static final long ENGINE_TIMEOUT_MINUTES = 30;

    private final AppConfig config;
    private final DataSource targetDs;

    public SnapshotStep(AppConfig config, DataSource targetDs) {
        this.config = config;
        this.targetDs = targetDs;
    }

    @Override
    public String name() { return "Snapshot"; }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepContext ctx) throws Exception {
        List<String> tables = ctx.get("tables", List.class);
        List<String> databases = ctx.get("databases", List.class);
        Integer batchSize = ctx.get("batchSize", Integer.class);
        Integer fetchSize = ctx.get("fetchSize", Integer.class);
        Integer snapshotThreads = ctx.get("snapshotThreads", Integer.class);
        Boolean autoEnableCdc = ctx.get("autoEnableCdc", Boolean.class);

        SnapshotConfig snapshotConfig = SnapshotConfig.defaults();
        if (batchSize != null) snapshotConfig = snapshotConfig.withBatchInsertSize(batchSize);
        if (fetchSize != null) snapshotConfig = snapshotConfig.withSnapshotFetchSize(fetchSize);
        if (snapshotThreads != null) snapshotConfig = snapshotConfig.withSnapshotMaxThreads(snapshotThreads);

        new File(snapshotConfig.offsetStoragePath()).mkdirs();
        new File(snapshotConfig.schemaHistoryPath()).mkdirs();

        System.out.print("  Discovering databases ... ");
        List<String> dbNames;
        try (Connection master = DriverManager.getConnection(
                config.getSource().sqlServerJdbcUrlNoDB(),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {
            dbNames = discoverDatabases(master);
        } catch (Exception e) {
            return StepResult.fatal("Cannot connect to SQL Server: " + e.getMessage());
        }
        System.out.println(dbNames.size() + " found");

        if (databases != null && !databases.isEmpty()) {
            dbNames = dbNames.stream().filter(databases::contains).toList();
        }
        if (dbNames.isEmpty()) {
            return StepResult.ok("no databases to snapshot");
        }

        CdcPreChecker cdcChecker = new CdcPreChecker(config.getSource());
        List<SnapshotDbResult> dbResults = new ArrayList<>();
        long totalRows = 0L;

        for (String dbName : dbNames) {
            SnapshotDbResult dbResult = snapshotDatabase(
                    dbName, tables, cdcChecker, snapshotConfig,
                    autoEnableCdc != null && autoEnableCdc);
            dbResults.add(dbResult);
            if (!dbResult.isError()) {
                totalRows += dbResult.rows();
            } else {
                log.error("[\"database snapshot failed\"] [database={}] [error=\"{}\"]",
                        dbName, dbResult.error());
            }
            printDbResult(dbName, dbResult);
        }

        ctx.put("snapshotSummaries", dbResults);
        ctx.put("snapshotTotalRows", totalRows);
        writeSnapshotMeta(dbResults, totalRows);

        if (SnapshotDbResult.shouldBlockPipeline(dbResults)) {
            return StepResult.fatal("snapshot completed with errors");
        }
        return StepResult.ok("snapshot complete, databases=" + dbResults.size() + " rows=" + totalRows);
    }

    private SnapshotDbResult snapshotDatabase(String dbName, List<String> tables,
                                               CdcPreChecker cdcChecker,
                                               SnapshotConfig snapshotConfig,
                                               boolean autoEnable) {
        try (Connection conn = DriverManager.getConnection(
                config.getSource().sqlServerJdbcUrlForDB(dbName),
                config.getSource().getUsername(),
                config.getSource().getPassword())) {

            List<String[]> tableList = discoverTables(conn, tables);
            CdcPreChecker.CdcCheckResult cdcResult = cdcChecker.check(conn, dbName, tableList, autoEnable);
            if (cdcResult.hasError()) {
                return new SnapshotDbResult(dbName, null, null, 0, 0L,
                        Instant.now(), cdcResult.errorMessage());
            }

            TiDBBatchWriter batchWriter = new TiDBBatchWriter(
                    targetDs, new SinkRecordConverter(), snapshotConfig.batchInsertSize());
            SnapshotSink sink = new SnapshotSink(batchWriter);

            DebeziumEngineFactory factory = new DebeziumEngineFactory(config.getSource());
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> engineError = new AtomicReference<>();

            DebeziumEngine<ChangeEvent<String, String>> engine = factory.create(
                    dbName, snapshotConfig, tables, sink, done::countDown);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    try {
                        engine.run();
                    } catch (Throwable t) {
                        engineError.set(t);
                        done.countDown();
                    }
                });
                boolean completed = done.await(ENGINE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (!completed) {
                    return new SnapshotDbResult(dbName, sink.getCommitLsn(), sink.getChangeLsn(),
                            tableList.size(), batchWriter.getTotalRows(), Instant.now(),
                            "Snapshot timed out after " + ENGINE_TIMEOUT_MINUTES + " minutes");
                }
                if (engineError.get() != null) {
                    return new SnapshotDbResult(dbName, null, null, 0, 0L,
                            Instant.now(), "Engine error: " + engineError.get().getMessage());
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.MINUTES);
                batchWriter.flushAll();
            }

            return new SnapshotDbResult(dbName, sink.getCommitLsn(), sink.getChangeLsn(),
                    tableList.size(), batchWriter.getTotalRows(), Instant.now(), null);
        } catch (Exception e) {
            log.error("[\"database snapshot error\"] [database={}] [error=\"{}\"]", dbName, e.getMessage());
            return new SnapshotDbResult(dbName, null, null, 0, 0L,
                    Instant.now(), e.getMessage());
        }
    }

    private List<String> discoverDatabases(Connection conn) throws Exception {
        List<String> dbs = new ArrayList<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT name FROM sys.databases WHERE name NOT IN ('master','tempdb','model','msdb') AND state_desc = 'ONLINE'")) {
            while (rs.next()) dbs.add(rs.getString("name"));
        }
        return dbs;
    }

    private List<String[]> discoverTables(Connection conn, List<String> tableFilter) throws Exception {
        List<String[]> tables = new ArrayList<>();
        String sql = "SELECT s.name, t.name FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id";
        if (tableFilter != null && !tableFilter.isEmpty()) {
            String inClause = tableFilter.stream().map(t -> "?").collect(java.util.stream.Collectors.joining(","));
            sql += " WHERE t.name IN (" + inClause + ")";
        }
        sql += " ORDER BY s.name, t.name";
        try (var ps = conn.prepareStatement(sql)) {
            if (tableFilter != null) {
                for (int i = 0; i < tableFilter.size(); i++) {
                    ps.setString(i + 1, tableFilter.get(i));
                }
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        return tables;
    }

    private void writeSnapshotMeta(List<SnapshotDbResult> dbResults, long totalRows) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"tool\": \"any2tidb\",\n");
            json.append("  \"command\": \"snapshot\",\n");
            json.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
            json.append("  \"databases\": {\n");
            for (int i = 0; i < dbResults.size(); i++) {
                SnapshotDbResult db = dbResults.get(i);
                json.append("    \"").append(db.dbName()).append("\": {\n");
                json.append("      \"commitLsn\": ").append(db.commitLsn() != null ? "\"" + db.commitLsn() + "\"" : "null").append(",\n");
                json.append("      \"changeLsn\": ").append(db.changeLsn() != null ? "\"" + db.changeLsn() + "\"" : "null").append(",\n");
                json.append("      \"tables\": ").append(db.tables()).append(",\n");
                json.append("      \"rows\": ").append(db.rows()).append("\n");
                json.append("    }");
                if (i < dbResults.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  },\n");
            json.append("  \"totalRows\": ").append(totalRows).append(",\n");
            int totalTables = dbResults.stream().mapToInt(SnapshotDbResult::tables).sum();
            json.append("  \"totalTables\": ").append(totalTables).append("\n");
            json.append("}\n");
            java.nio.file.Files.writeString(java.nio.file.Path.of("snapshot-meta.json"), json);
            log.info("[\"snapshot-meta.json written\"]");
        } catch (Exception e) {
            log.warn("[\"failed to write snapshot-meta.json\"] [error=\"{}\"]", e.getMessage());
        }
    }

    private void printDbResult(String dbName, SnapshotDbResult r) {
        if (r.isError()) {
            System.out.printf("  %-20s  ERROR: %s%n", dbName, r.error());
        } else {
            System.out.printf("  %-20s  %d tables, %,d rows, LSN=%s%n",
                    dbName, r.tables(), r.rows(),
                    r.commitLsn() != null ? r.commitLsn() : "(none)");
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl . -Dtest=com.tool.snapshot.SnapshotStepTest -q -Djacoco.skip=true`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tool/snapshot/SnapshotStep.java src/test/java/com/tool/snapshot/SnapshotStepTest.java
git commit -m "feat(snapshot): add SnapshotStep orchestrator with Debezium engine"
```

---

### Task 11: App.java Wiring

**Files:**
- Modify: `src/main/java/com/tool/App.java`

- [ ] **Step 1: Add SnapshotCommand import and subcommand**

At the top of App.java, add import:
```java
import com.tool.cli.SnapshotCommand;
```

Change the `@Command` annotation on `TopLevelCommand` (line 70) to include SnapshotCommand:
```java
@Command(name = "any2tidb", mixinStandardHelpOptions = true,
        subcommands = {SchemaCommand.class, DumpCommand.class, SnapshotCommand.class})
```

- [ ] **Step 2: Add printSnapshotHelp() method**

After `printDumpHelp()` (around line 144), add:

```java
private static void printSnapshotHelp() {
    System.out.println(
"""
any2tidb snapshot -- Export data directly to TiDB via Debezium CDC

Usage:
  any2tidb snapshot [options]

Options:
  --tables=t1,t2,...       Only process specified tables (default: all)
  --dbs=db1,db2            Only process specified databases (default: all)
  --batch-size=N           INSERT batch size (default: 5000)
  --fetch-size=N           Debezium snapshot fetch size (default: 2000)
  --snapshot-threads=N     Debezium parallel chunk threads (default: 1)
  --auto-enable-cdc        Automatically enable CDC on SQL Server
  --help, -h               Show this help message

Prerequisites:
  SQL Server Agent must be running
  CDC must be enabled on target databases and tables
  Run 'any2tidb schema' first to create TiDB tables

Examples:
  any2tidb snapshot
  any2tidb snapshot --dbs=Authentication --tables=User
  any2tidb snapshot --auto-enable-cdc --snapshot-threads=4
""");
}
```

- [ ] **Step 3: Add snapshot to help routing**

In the `main()` method's help block (around line 156), add:
```java
if ("snapshot".equals(sub)) { printSnapshotHelp(); System.exit(0); }
```

In the parse error block (around line 169), add:
```java
else if ("snapshot".equals(sub)) printSnapshotHelp();
```

Update `printHelp()` (around line 87) to include snapshot:
```java
Commands:
  schema    Migrate schema (CREATE TABLE + INDEX) to TiDB
  dump      Export data as CSV files
  snapshot  Export data directly to TiDB via Debezium CDC
```

- [ ] **Step 4: Add run() branch**

In the `run()` method (around line 197), add:
```java
} else if (parsedCommand instanceof SnapshotCommand snapshot) {
    runSnapshot(snapshot, progress);
}
```

- [ ] **Step 5: Add runSnapshot() method**

After `runDump()` (around line 241), add:

```java
private void runSnapshot(SnapshotCommand snapshot, ProgressReporter progress) throws Exception {
    String command = "snapshot";
    System.out.println();
    System.out.printf("any2tidb %s | %s → %s%n",
            command, connInfo(config.getSource()), connInfo(config.getTarget()));
    System.out.println(SEP);

    java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
    if (snapshot.databases != null && !snapshot.databases.isEmpty())
        filters.put("databases", String.join(", ", snapshot.databases));
    if (snapshot.tables != null && !snapshot.tables.isEmpty())
        filters.put("tables", String.join(", ", snapshot.tables));
    if (snapshot.batchSize != 5000)
        filters.put("batch-size", String.valueOf(snapshot.batchSize));
    if (snapshot.fetchSize != 2000)
        filters.put("fetch-size", String.valueOf(snapshot.fetchSize));
    if (snapshot.snapshotThreads != 1)
        filters.put("threads", String.valueOf(snapshot.snapshotThreads));
    if (snapshot.autoEnableCdc)
        filters.put("auto-enable-cdc", "true");
    printFilters(filters);

    System.out.println();
    System.out.println("── Snapshot " + SEP.substring(11));
    System.out.println();

    log.info("[\"any2tidb started\"] [command={}]", command);

    StepContext ctx = new StepContext();
    ctx.put("tablesOverride",   snapshot.tables);
    ctx.put("databasesOverride", snapshot.databases);
    ctx.put("batchSize",        snapshot.batchSize);
    ctx.put("fetchSize",        snapshot.fetchSize);
    ctx.put("snapshotThreads",  snapshot.snapshotThreads);
    ctx.put("autoEnableCdc",    snapshot.autoEnableCdc);

    List<MigrationStep> steps = new ArrayList<>();
    steps.add(new PreCheckStep(config, false));
    steps.add(new SnapshotStep(config, targetDs));

    executePipeline(ctx, steps, command);
}
```

- [ ] **Step 6: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Run all tests to verify no regressions**

Run: `mvn test -q`
Expected: All existing tests pass + new snapshot tests pass

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tool/App.java
git commit -m "feat(snapshot): wire SnapshotCommand into App with runSnapshot pipeline"
```

---

### Task 12: Full Build and Smoke Test

**Files:** None new

- [ ] **Step 1: Run full test suite**

Run: `mvn test -q`
Expected: All tests pass (existing + new snapshot tests)

- [ ] **Step 2: Verify JAR packaging includes Debezium services**

Run: `mvn package -q && jar tf dist/lib/any2tidb-1.0.0.jar | grep -i debezium | head -20`
Expected: Debezium classes present in JAR

Run: `jar tf dist/lib/any2tidb-1.0.0.jar | grep 'META-INF/services' | grep -i debezium`
Expected: SPI service files present (e.g., `io.debezium.spi.server.Version`)

- [ ] **Step 3: Verify CLI help works**

Run: `java -jar dist/lib/any2tidb-1.0.0.jar --help`
Expected: Shows `snapshot` in commands list

Run: `java -jar dist/lib/any2tidb-1.0.0.jar snapshot --help`
Expected: Shows snapshot-specific options

- [ ] **Step 4: Commit any remaining fixes**

```bash
git add -A
git commit -m "feat(snapshot): verify build, packaging, and CLI integration"
```

---

### Task 13: Cleanup — Remove JaCoCo Exclusion

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Remove snapshot JaCoCo exclusion**

Remove the line added in Task 1:
```xml
<exclude>com/tool/snapshot/**</exclude>
```

- [ ] **Step 2: Run tests to check coverage**

Run: `mvn test -q`
Expected: Tests pass. If JaCoCo `check` goal fails due to low snapshot coverage, add targeted tests or raise the threshold issue for discussion.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: remove snapshot JaCoCo exclusion"
```
