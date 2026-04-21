# Snapshot Module Design — Debezium-based Streaming Full Data Sync

## 1. Overview

New `snapshot` module parallel to `schema` and `dump`. Uses Debezium Embedded Engine 3.5.0.Final to perform streaming full data sync from SQL Server to TiDB, recording LSN for future incremental CDC.

**Key constraints:**
- Fully decoupled from `schema` and `dump` modules
- Zero modification to existing module source files (except App.java wiring)
- Requires SQL Server CDC enabled (with optional auto-enable)

## 2. Architecture

```
any2tidb snapshot [options]
       |
       v
  SnapshotCommand (picocli POJO)
       |
       v
  App.runSnapshot()
       |
       v
  Pipeline: PreCheckStep -> SnapshotStep
                         |
                         +-- CdcPreChecker (CDC prerequisites)
                         +-- DebeziumEngine (AsyncEmbeddedEngine, one per DB)
                         |      |
                         |      +-- SqlServerConnector (source)
                         |      +-- SnapshotSink (JSON parser)
                         |                |
                         |                +-- SinkRecordConverter (Connect -> JDBC)
                         |                +-- TiDBBatchWriter (batch INSERT to TiDB)
                         |
                         +-- Progress table (same format as dump)
                         +-- snapshot-meta.json (LSN + stats)
```

## 3. Module Structure

```
com.tool.snapshot/
+-- SnapshotStep.java              -- MigrationStep implementation (orchestrator)
+-- SnapshotConfig.java            -- Debezium + snapshot configuration
+-- engine/
|   +-- DebeziumEngineFactory.java -- builds AsyncEmbeddedEngine per DB
+-- sink/
|   +-- SnapshotSink.java          -- Plain POJO, parses JSON events
|   +-- SnapshotJsonParser.java   -- Jackson JSON parser for Debezium envelopes
|   +-- TiDBBatchWriter.java       -- batch INSERT to TiDB
   +-- SinkRecordConverter.java   -- Map<String,Object> -> JDBC type mapping
+-- cdc/
|   +-- CdcPreChecker.java         -- verifies CDC prerequisites on SQL Server
+-- model/
    +-- SnapshotTableResult.java   -- per-table result record
```

## 4. Dependency Matrix

### What snapshot depends on:

| Dependency | Type | Reason |
|---|---|---|
| `pipeline` (MigrationStep, StepContext, StepResult) | Interface impl | Plug into Pipeline framework |
| `config` (AppConfig.DbConfig) | Read config | Source/target connection params |
| `output` (ProgressReporter) | Progress display | Console progress (zero coupling) |
| `targetDataSource` Bean | Reuse pool | Write to TiDB |
| Debezium (embedded, api, connector-sqlserver) | New Maven dep | Snapshot engine |
| Jackson Databind (transitive from Debezium) | JSON parsing | Parse Debezium JSON events |

### What snapshot does NOT depend on:

- `schema/*` -- zero imports
- `dump/*` -- zero imports
- `common/source/SourceCatalog` -- Debezium handles table discovery
- `output/SummaryPrinter` -- coupled to schema types, not reusable

### Impact on existing modules:

| Module | Impact |
|---|---|
| `schema` | None |
| `dump` | None |
| `pipeline` | None (only implements MigrationStep interface) |
| `common/source` | None |
| `config/AppConfig` | None (only reads existing DbConfig) |
| `output` | None (only uses ProgressReporter) |
| `App.java` | Minimal: add runSnapshot(), printSnapshotHelp(), if-else branch |
| `pom.xml` | Add-only: 3 Debezium dependencies |
| `application.yml` | Optional: add snapshot: config section |

## 5. File-by-File Design

### 5.1 SnapshotConfig.java

Configuration for Debezium engine and snapshot behavior. Bound from `application.yml` `snapshot:` section + CLI overrides.

```java
package com.tool.snapshot;

public class SnapshotConfig {
    private final String offsetStoragePath;     // "snapshot-offsets"
    private final String schemaHistoryPath;     // "snapshot-schema-history"
    private final String snapshotMode;          // "initial_only"
    private final int batchInsertSize;          // 5000
    private final int snapshotFetchSize;        // 2000
    private final int maxQueueSize;             // 8192
    private final int pollIntervalMs;           // 500
    private final int offsetCommitIntervalMs;   // 10000
    private final int snapshotMaxThreads;       // 1 (Debezium 3.5 parallel chunked)
    private final double snapshotMaxThreadsMultiplier; // 1.0
}
```

### 5.2 SnapshotStep.java

Implements `MigrationStep`. Orchestration flow:

1. **CDC Pre-check** -- run CdcPreChecker against each target database
2. **Per-database sequential processing** (each DB needs its own Debezium engine):
   - Build engine via DebeziumEngineFactory
   - Run engine (blocking) with SnapshotSink accepting JSON events
   - Collect SnapshotTableResult
3. **Write snapshot-meta.json** with LSN per database
4. **Print progress table** (same format as DumpStep)

Database processing is sequential for error isolation and connection management. Table-level parallelism is handled internally by Debezium via `snapshot.max.threads`.

### 5.3 DebeziumEngineFactory.java

Builds `AsyncEmbeddedEngine` per database using Debezium 3.5 verified API:

```java
Properties props = new Properties();
props.setProperty("name", "any2tidb-snapshot-" + dbName);
props.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
props.setProperty("database.hostname", source.getHost());
props.setProperty("database.port", String.valueOf(source.getPort()));
props.setProperty("database.user", source.getUsername());
props.setProperty("database.password", source.getPassword());
props.setProperty("database.dbname", dbName);
props.setProperty("database.server.name", "any2tidb_" + dbName);
props.setProperty("database.history.file.filename", schemaHistoryPath + "/" + dbName + ".history");
props.setProperty("offset.storage.file.filename", offsetStoragePath + "/" + dbName + ".offset");
props.setProperty("snapshot.mode", "initial_only");
props.setProperty("snapshot.fetch.size", String.valueOf(fetchSize));
props.setProperty("max.queue.size", String.valueOf(maxQueueSize));
props.setProperty("poll.interval.ms", String.valueOf(pollIntervalMs));
props.setProperty("offset.flush.interval.ms", String.valueOf(offsetCommitIntervalMs));
props.setProperty("snapshot.max.threads", String.valueOf(maxThreads));
props.setProperty("table.include.list", buildTableIncludeList(tables));

// Debezium 3.5 API: create(Json.class) → Builder<ChangeEvent<String, String>>
// .notifying(Consumer<ChangeEvent<String, String>>) — each event value is JSON
DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class)
        .using(props)
        .notifying(sink::accept)   // sink.accept(List<ChangeEvent>) adapts to batch
        .using((CompletionCallback) (success, msg, error) -> { ... })
        .build();
```

Engine execution: `engine` implements `Runnable`. Run via `executor.submit(engine::run)`. Blocks until snapshot completes or times out. No `engine.await()` method exists in 3.5.

### 5.4 SnapshotSink.java

Plain POJO (NOT a ChangeConsumer). Receives Debezium `ChangeEvent<String, String>` batches, parses JSON, routes to TiDBBatchWriter.

```java
public class SnapshotSink {
    private final TiDBBatchWriter batchWriter;
    private final SnapshotJsonParser parser;  // Jackson ObjectMapper wrapper

    /**
     * Called by DebeziumEngine .notifying() callback.
     * Adapts List<ChangeEvent<String, String>> to individual events.
     */
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
                log.error("record failed: {}", e.getMessage());
            }
        }
    }
}
```

### 5.5 SnapshotJsonParser.java (new)

Jackson-based JSON parser for Debezium event envelopes. Extracts:

```java
public class SnapshotJsonParser {
    private final ObjectMapper mapper = new ObjectMapper();

    record ParsedRecord(
        String dbName, String schema, String table,
        Map<String, Object> before, Map<String, Object> after,
        String op, String commitLsn, String changeLsn, String snapshot
    ) {
        boolean isSnapshot() { return "true".equals(snapshot) || "last".equals(snapshot); }
    }

    ParsedRecord parse(String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode source = root.get("source");
        JsonNode payload = root.get("payload");
        // topic format: any2tidb_<dbName>.<schema>.<table>
        String topic = root.path("topic").asText();
        // OR extract from source.db / source.table
        String dbName = source.path("db").asText();
        String schema = source.path("schema").asText();
        String table = source.path("table").asText();
        // Extract commit_lsn / change_lsn from source
        String commitLsn = source.path("commit_lsn").asText();
        String changeLsn = source.path("change_lsn").asText();
        // Extract before/after as Map<String, Object>
        Map<String, Object> after = toMap(payload.get("after"));
        String snapshot = source.path("snapshot").asText();
        return new ParsedRecord(dbName, schema, table, null, after,
                root.path("op").asText(), commitLsn, changeLsn, snapshot);
    }
}
```

**Note:** The exact JSON structure depends on Debezium's `Json` serialization format. The parser will be refined during Task 8 implementation after observing actual Debezium output. Key fields (`after`, `source.commit_lsn`, `source.snapshot`) are standard Debezium envelope fields.

### 5.6 TiDBBatchWriter

- Map-based buffering by table name: `Map<String, List<Map<String, Object>>>`
- Multi-value INSERT: `INSERT INTO db.table (col1,col2) VALUES (?,?),(?,?)...`
- Flush at `batchInsertSize` threshold
- Uses injected `targetDataSource` (HikariCP pool)
- Thread-safe: single-writer (SnapshotSink is called sequentially by Debezium)
- Tracks per-table and total row counts

### 5.7 SinkRecordConverter

Maps JSON-parsed `Map<String, Object>` values to JDBC `PreparedStatement` methods:

| JSON value type (Jackson) | JDBC Method | SQL Server Source Types |
|---|---|---|
| Integer / Short | `setInt` | tinyint, smallint, int |
| Long | `setLong` | bigint |
| Double / Float | `setDouble` | real, float |
| BigDecimal | `setBigDecimal` | decimal, numeric, money |
| String (timestamp pattern) | `setTimestamp` | datetime, datetime2 |
| String | `setString` | varchar, nvarchar, char |
| byte[] | `setBytes` | varbinary, binary |
| Boolean | `setBoolean` | bit |
| null | `setNull` | any nullable column |

Simpler than `schema/TypeMapper` because Jackson auto-resolves JSON types to Java types.

### 5.7 CdcPreChecker

Checks per database (no SQL Server edition check):

| Check | SQL | Failure |
|---|---|---|
| SQL Server Agent running | `sys.dm_exec_sessions WHERE program_name LIKE 'SQLAgent%'` | Fatal + startup instructions |
| DB-level CDC enabled | `sys.databases.is_cdc_enabled` | autoEnableCdc=true: auto-enable; else Fatal |
| Table-level CDC capture | `cdc.change_tables` | autoEnableCdc=true: auto-enable; else Fatal + list missing tables |

Called inside SnapshotStep, not a separate MigrationStep. CDC is snapshot-specific, keeping PreCheckStep generic.

### 5.8 SnapshotTableResult.java

```java
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

## 6. CLI Integration

### SnapshotCommand.java

```java
@Command(name = "snapshot", mixinStandardHelpOptions = true)
public class SnapshotCommand implements Runnable {
    @Option(names = "--tables", split = ",")   List<String> tables;
    @Option(names = "--dbs", split = ",")      List<String> databases;
    @Option(names = "--batch-size", defaultValue = "5000")  int batchSize;
    @Option(names = "--fetch-size", defaultValue = "2000")  int fetchSize;
    @Option(names = "--snapshot-threads", defaultValue = "1")  int snapshotThreads;
    @Option(names = "--auto-enable-cdc")       boolean autoEnableCdc;
}
```

### App.java changes

1. Add `SnapshotCommand.class` to `TopLevelCommand` subcommands
2. Add `printSnapshotHelp()` static method
3. Add `runSnapshot(SnapshotCommand, ProgressReporter)` method
4. Add `if (parsedCommand instanceof SnapshotCommand snapshot)` branch in `run()`
5. Update `printHelp()` with snapshot subcommand

### StepContext data flow

**Written by App.runSnapshot():**
- `"tablesOverride"` -> List<String>
- `"databasesOverride"` -> List<String>
- `"batchSize"` -> int
- `"fetchSize"` -> int
- `"snapshotThreads"` -> int
- `"autoEnableCdc"` -> boolean

**Normalized by PreCheckStep (shared with schema/dump):**
- `"tables"` -> List<String>
- `"databases"` -> List<String>

**Read by SnapshotStep:**
- `"tables"`, `"databases"` (from PreCheckStep)
- `"batchSize"`, `"fetchSize"`, `"snapshotThreads"`, `"autoEnableCdc"` (direct)

**Written by SnapshotStep:**
- `"snapshotSummaries"` -> List<SnapshotTableResult>
- `"snapshotTotalRows"` -> long

All output keys use `snapshot` prefix to avoid collision with schema (`dbSummaries`, `totalFailed`) and dump (`dumpSummaries`, `dumpTotalRows`).

### CLI help text

```
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
```

## 7. LSN Tracking and Metadata Output

Debezium includes LSN in every change event's `source` block:

```json
{
  "source": {
    "commit_lsn": "00000025:00000338:0003",
    "change_lsn": "00000025:00000338:0002"
  }
}
```

SnapshotSink extracts and tracks the maximum LSN per database.

### snapshot-meta.json format

```json
{
  "tool": "any2tidb",
  "command": "snapshot",
  "timestamp": "2026-04-21T10:30:00Z",
  "databases": {
    "Authentication": {
      "commitLsn": "00000025:00000338:0003",
      "changeLsn": "00000025:00000338:0002",
      "tables": 12,
      "rows": 45230
    }
  },
  "totalRows": 173230,
  "totalTables": 20
}
```

Consistent format with existing `dump-meta.json`. Provides resume point for future incremental CDC.

## 8. Error Handling

| Scenario | Action | Scope |
|---|---|---|
| SQL Server Agent not running | Fatal + startup instructions | Abort snapshot |
| DB-level CDC not enabled | autoEnableCdc=true: auto-enable; else Fatal | Per-DB |
| Table-level CDC not enabled | autoEnableCdc=true: auto-enable; else Fatal + list tables | Per-DB |
| Single record conversion failure | Log error + skip record | Per-record |
| TiDB batch INSERT failure | Retry single-row to find bad row, skip it | Per-batch |
| Debezium Engine fatal error | Abort DB, continue to next DB | Per-DB |
| Target table does not exist | Fatal + "run any2tidb schema first" | Per-DB |
| Offset file corruption | Delete offset file, restart (idempotent) | Per-DB |

## 9. Maven Dependencies

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

No Kafka deployment required. `FileOffsetBackingStore` for offsets, `FileSchemaHistory` for schema history.

### Packaging

Spring Boot `spring-boot-maven-plugin` should merge `META-INF/services` for Debezium SPI loading. If SPI loading fails at runtime, switch to `maven-shade-plugin` with `ServicesResourceTransformer`.

JAR size increase: approximately 50-80MB (Kafka Connect API + Debezium core + SQL Server Connector).

## 10. application.yml additions (optional)

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

## 11. Implementation Order

1. Add Maven dependencies, verify build and packaging
2. `SnapshotConfig` + `application.yml` section
3. `SnapshotCommand` CLI subcommand
4. `SnapshotTableResult` model
5. `CdcPreChecker` CDC prerequisite validation
6. `DebeziumEngineFactory` engine builder
7. `SinkRecordConverter` Connect -> JDBC mapping
8. `TiDBBatchWriter` batch INSERT
9. `SnapshotSink` ChangeConsumer
10. `SnapshotStep` MigrationStep orchestrator
11. `App.java` wiring + `printSnapshotHelp()`
12. Unit tests for each component
13. Integration test against real SQL Server + TiDB

## 12. Key Design Decisions

| Decision | Rationale |
|---|---|
| Consumer + JSON (not ChangeConsumer) | Debezium 3.5 Json format yields ChangeEvent<String,String>. Simple Consumer callback is sufficient. |
| Sequential DB processing | Each DB = separate engine; simpler error isolation |
| snapshot.mode=initial_only | any2tidb scope = one-time full sync, not continuous replication |
| SinkRecordConverter separate from TypeMapper | Debezium resolves SS types to Connect types; simpler mapping |
| CdcPreChecker inside SnapshotStep | CDC is snapshot-specific; keeps PreCheckStep generic |
| Reuse targetDataSource | Avoid duplicate connection pool config |
| Require schema first | Responsibility separation; snapshot does not create tables |
| LSN in snapshot-meta.json | Same format as dump-meta.json; enables future CDC resume |
| auto-enable-cdc opt-in | CDC requires sysadmin; don't modify without explicit consent |
| Debezium 3.5.0.Final | 3.4 has fatal initial_only bug; 3.5 adds parallel chunked snapshots |
