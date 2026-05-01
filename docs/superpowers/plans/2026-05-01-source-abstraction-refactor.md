# Source Abstraction Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate 6 CRITICAL SQL Server abstraction leaks so adding a second source database type (Postgres, MySQL) only requires new interface implementations, not rewrites of pipeline steps.

**Architecture:** Follow the existing pattern — `SchemaExtractor`/`SqlServerExtractor` is the gold standard. Every source-specific concern gets an interface in the generic package and a `SqlServer*` implementation. Pipeline steps only talk to interfaces.

**Tech Stack:** Java 17, Spring Boot, Debezium

---

## File Map

| File | Role |
|------|------|
| `source/SourceDriver.java` | **Expanded** — add `buildJdbcUrl()`, `cdcProvider()`, `debeziumConnectorClass()` |
| `source/SqlServerDriver.java` | **Expanded** — implement new SourceDriver methods |
| `source/CdcProvider.java` | **NEW** — CDC pre-check interface |
| `source/SqlServerCdcProvider.java` | **NEW** — SQL Server CDC implementation (code from CdcPreChecker) |
| `snapshot/cdc/CdcPreChecker.java` | **DELETED** — code moved to SqlServerCdcProvider |
| `common/CdcUtils.java` | **MOVED** → `source/sqlserver/SqlServerCdcUtils.java` |
| `schema/converter/TypeMapper.java` | **RENAMED** → `schema/converter/SqlServerTypeMapper.java` |
| `schema/verifier/SchemaVerifier.java` | **Split** into interface + `SqlServerSchemaVerifier` |
| `schema/extractor/SchemaExtractor.java` | **Expanded** — add `estimateRowCounts()` |
| `schema/extractor/SqlServerExtractor.java` | **Expanded** — implement `estimateRowCounts()` |
| `source/DatabaseSnapshotManager.java` | **RENAMED** → `source/SqlServerConsistencyProvider.java` |
| `snapshot/SnapshotStep.java` | **Modified** — use SchemaExtractor, CdcProvider, SourceDriver |
| `pipeline/steps/DumpStep.java` | **Modified** — fix hardcoded URL, use SourceDriver |
| `pipeline/steps/SchemaMigrateStep.java` | **Modified** — use SourceDriver for JDBC URL |
| `pipeline/steps/PreCheckStep.java` | **Modified** — use SourceDriver for JDBC URL |
| `pipeline/steps/VerifyStep.java` | **Modified** — use SourceDriver for JDBC URL |
| `snapshot/engine/DebeziumEngineFactory.java` | **Modified** — connector class from SourceDriver |
| `sync/SyncEngineFactory.java` | **Modified** — connector class from SourceDriver |
| `App.java` | **Modified** — pass SourceDriver to runners |
| `SchemaDumpRunner.java` | **Modified** — pass SourceDriver to steps |
| `SnapshotRunner.java` | **Modified** — pass SourceDriver to SnapshotStep |
| `SyncRunner.java` | **Modified** — pass SourceDriver to SyncStep |
| `config/AppConfig.java` | **Modified** — remove `jdbcUrl()` / `jdbcUrlTo()` |

---

## Phase 1: Easy Wins (rename + move, no API changes)

### Task 1: Move CdcUtils from `common` to `source/sqlserver`

**Files:**
- Create: `src/main/java/com/tool/source/sqlserver/SqlServerCdcUtils.java`
- Delete: `src/main/java/com/tool/common/CdcUtils.java`
- Modify: all files that import `com.tool.common.CdcUtils`

- [ ] **Step 1: Create the new file**

```bash
mkdir -p src/main/java/com/tool/source/sqlserver
```

Copy `src/main/java/com/tool/common/CdcUtils.java` to `src/main/java/com/tool/source/sqlserver/SqlServerCdcUtils.java`. Change the class name from `CdcUtils` to `SqlServerCdcUtils` and the package from `com.tool.common` to `com.tool.source.sqlserver`. All method bodies unchanged.

```java
package com.tool.source.sqlserver;

// ... same imports ...

/**
 * SQL Server CDC LSN utilities — capture and format for Debezium compatibility.
 */
public final class SqlServerCdcUtils {

    private SqlServerCdcUtils() {}

    public static String captureLsn(String host, int port, String user, String password, String dbName)
            throws Exception {
        // ... identical body ...
    }

    public static String hexLsnToDebezium(String hexLsn) {
        // ... identical body ...
    }

    public static void writeDebeziumOffset(String path, String dbName, String debeziumLsn)
            throws IOException {
        // ... identical body ...
    }

    @SuppressWarnings("unchecked")
    public static void patchOffsetLsn(String path, String dbName, String newDebeziumLsn)
            throws Exception {
        // ... identical body ...
    }

    private static String decodeOffsetEntry(Object entry) {
        // ... identical body ...
    }
}
```

- [ ] **Step 2: Update all imports**

Find all files importing `com.tool.common.CdcUtils` and update:

```
import static com.tool.common.CdcUtils.captureLsn;
→ import static com.tool.source.sqlserver.SqlServerCdcUtils.captureLsn;

import static com.tool.common.CdcUtils.hexLsnToDebezium;
→ import static com.tool.source.sqlserver.SqlServerCdcUtils.hexLsnToDebezium;

import static com.tool.common.CdcUtils.writeDebeziumOffset;
→ import static com.tool.source.sqlserver.SqlServerCdcUtils.writeDebeziumOffset;

import static com.tool.common.CdcUtils.patchOffsetLsn;
→ import static com.tool.source.sqlserver.SqlServerCdcUtils.patchOffsetLsn;
```

Files to update:
- `pipeline/steps/DumpStep.java` (lines 17-19)
- `source/DatabaseSnapshotManager.java` (line 3)
- `sync/SyncStep.java` (line 16 — may use full qualified name)

- [ ] **Step 3: Delete old file**

Delete `src/main/java/com/tool/common/CdcUtils.java`.

- [ ] **Step 4: Compile to verify**

```bash
mvn compile -q && echo "OK"
```

Expected: `OK`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move CdcUtils from common to source/sqlserver/SqlServerCdcUtils"
```

---

### Task 2: Rename TypeMapper → SqlServerTypeMapper

**Files:**
- Rename: `src/main/java/com/tool/schema/converter/TypeMapper.java` → `SqlServerTypeMapper.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java`
- Modify: `src/main/java/com/tool/schema/converter/SchemaConverter.java` (if it references TypeMapper directly)
- Modify: `src/main/java/com/tool/schema/verifier/SchemaVerifier.java`

- [ ] **Step 1: Rename the file and class**

Create `src/main/java/com/tool/schema/converter/SqlServerTypeMapper.java` — same content, class renamed to `SqlServerTypeMapper`.

- [ ] **Step 2: Update SqlServerDriver**

In `SqlServerDriver.java`, change `TypeMapper` → `SqlServerTypeMapper` in the field, constructor parameter, and return type.

```java
// field
private final SqlServerTypeMapper typeMapper;

// constructor
public SqlServerDriver(SchemaExtractor schemaExtractor,
                       DumpExtractor dumpExtractor,
                       SqlServerTypeMapper typeMapper,
                       ...) {
    this.typeMapper = typeMapper;
    // ...
}

// method
@Override
public SqlServerTypeMapper typeMapper() { return typeMapper; }
```

- [ ] **Step 3: Update SourceDriver interface**

In `SourceDriver.java`, update the `typeMapper()` return type from `TypeMapper` to `SqlServerTypeMapper`.

Actually — better approach: keep `TypeMapper` as the return type. Just check if it's only accessed through `SourceDriver` already. If so, nothing else needs to change.

Check: `grep -r "TypeMapper" --include="*.java" | grep -v "import" | grep -v SqlServerExtractor`

Only `SchemaVerifier` and `SchemaConverter` receive `TypeMapper` — they get it injected by Spring. Since `SqlServerTypeMapper` extends nothing currently, they'd need to be updated too.

Since `TypeMapper` is `@Component` and injected by Spring, we need to update:
- `SchemaVerifier.java` — field type
- `SchemaConverter.java` — field type (if it stores TypeMapper)

- [ ] **Step 4: Delete old file, verify compile**

```bash
rm src/main/java/com/tool/schema/converter/TypeMapper.java
mvn compile -q && echo "OK"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename TypeMapper → SqlServerTypeMapper"
```

---

### Task 3: Rename DatabaseSnapshotManager → SqlServerConsistencyProvider

**Files:**
- Rename: `src/main/java/com/tool/source/DatabaseSnapshotManager.java` → `SqlServerConsistencyProvider.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java:30`

- [ ] **Step 1: Create renamed file**

Create `SqlServerConsistencyProvider.java` with class renamed from `DatabaseSnapshotManager` to `SqlServerConsistencyProvider`. All method bodies unchanged.

- [ ] **Step 2: Update SqlServerDriver**

```java
// line 30
this.consistencyProvider = new SqlServerConsistencyProvider(
        config.getSource().getHost(),
        config.getSource().getPort(),
        config.getSource().getUsername(),
        config.getSource().getPassword());
```

Also update the import and field type from `DatabaseSnapshotManager` to `SqlServerConsistencyProvider` (or keep it as `ConsistencyProvider` which is cleaner).

- [ ] **Step 3: Delete old file and compile**

```bash
rm src/main/java/com/tool/source/DatabaseSnapshotManager.java
mvn compile -q && echo "OK"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: rename DatabaseSnapshotManager → SqlServerConsistencyProvider"
```

---

## Phase 2: Interface Extraction

### Task 4: Extract SchemaVerifier interface

**Files:**
- Create: `src/main/java/com/tool/schema/verifier/SchemaVerifier.java` (interface)
- Rename: `src/main/java/com/tool/schema/verifier/SchemaVerifier.java` → `SqlServerSchemaVerifier.java` (but as a new file, then delete old)
- Modify: `src/main/java/com/tool/source/SourceDriver.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java`
- Modify: `src/main/java/com/tool/pipeline/steps/VerifyStep.java`

- [ ] **Step 1: Write the interface**

Replace current `SchemaVerifier.java` with the interface:

```java
package com.tool.schema.verifier;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface SchemaVerifier {
    VerifyResult verify(Connection srcConn, Connection tidbConn,
                        String schema, String tableName) throws SQLException;

    List<VerifyResult> verifyAll(Connection srcConn, Connection tidbConn,
                                 List<String[]> tableList) throws SQLException;
}
```

- [ ] **Step 2: Create SqlServerSchemaVerifier**

Create new file with the old concrete implementation, class renamed:

```java
package com.tool.schema.verifier;

import com.tool.schema.converter.SqlServerTypeMapper;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;
import static com.tool.common.SqlUtils.escapeBracket;

@Component
public class SqlServerSchemaVerifier implements SchemaVerifier {

    private final SqlServerTypeMapper typeMapper;

    public SqlServerSchemaVerifier(SqlServerTypeMapper typeMapper) {
        this.typeMapper = typeMapper;
    }

    @Override
    public VerifyResult verify(Connection srcConn, Connection tidbConn,
                               String schema, String tableName) throws SQLException {
        // ... all existing verify() body unchanged ...
    }

    @Override
    public List<VerifyResult> verifyAll(Connection srcConn, Connection tidbConn,
                                        List<String[]> tableList) throws SQLException {
        // ... all existing verifyAll() body unchanged ...
    }

    private String normalizeDefault(String raw) {
        // ... unchanged ...
    }
}
```

- [ ] **Step 3: Update SourceDriver and SqlServerDriver**

In `SourceDriver.java`, change `verifier()` return type:

```java
SchemaVerifier verifier();
```

In `SqlServerDriver.java`:

```java
private final SchemaVerifier verifier;

public SqlServerDriver(..., SchemaVerifier verifier, ...) {
    this.verifier = verifier;
}

@Override
public SchemaVerifier verifier() { return verifier; }
```

- [ ] **Step 4: Update VerifyStep**

```java
// field type
private final SchemaVerifier verifier;

// constructor
public VerifyStep(AppConfig config, SchemaVerifier verifier) {
    this.verifier = verifier;
}
```

- [ ] **Step 5: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: extract SchemaVerifier interface, move SQL Server impl to SqlServerSchemaVerifier"
```

---

### Task 5: Extract CdcProvider interface, add to SourceDriver

**Files:**
- Create: `src/main/java/com/tool/source/CdcProvider.java` (interface)
- Create: `src/main/java/com/tool/source/SqlServerCdcProvider.java` (impl)
- Delete: `src/main/java/com/tool/snapshot/cdc/CdcPreChecker.java`
- Modify: `src/main/java/com/tool/source/SourceDriver.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java`
- Modify: `src/main/java/com/tool/snapshot/SnapshotStep.java`

- [ ] **Step 1: Create CdcProvider interface**

```java
package com.tool.source;

import java.sql.Connection;
import java.util.List;

public interface CdcProvider {

    record CdcCheckResult(
            boolean hasError,
            String errorMessage,
            boolean agentRunning,
            boolean cdcEnabled,
            List<String> tablesWithoutCdc
    ) {}

    boolean isAgentRunning(Connection conn) throws Exception;

    boolean isCdcEnabled(Connection conn, String dbName) throws Exception;

    List<String> getTablesWithoutCdc(Connection conn, String dbName,
                                     List<String[]> tables) throws Exception;

    void enableCdc(Connection conn, String dbName) throws Exception;

    void enableCdcForTable(Connection conn, String dbName, String schema, String table) throws Exception;

    CdcCheckResult check(Connection conn, String dbName,
                          List<String[]> tables, boolean autoEnable);
}
```

- [ ] **Step 2: Create SqlServerCdcProvider**

Move all SQL Server CDC logic from `CdcPreChecker.java` into this class. Add `@Component` (or not — it's instantiated manually in SqlServerDriver). Keep method bodies identical.

```java
package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SqlServerCdcProvider implements CdcProvider {

    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcProvider.class);
    private final AppConfig.DbConfig source;

    public SqlServerCdcProvider(AppConfig.DbConfig source) {
        this.source = source;
    }

    // ... all methods from CdcPreChecker, unchanged bodies ...
}
```

- [ ] **Step 3: Add cdcProvider() to SourceDriver**

```java
CdcProvider cdcProvider();
```

- [ ] **Step 4: Wire in SqlServerDriver**

```java
private final CdcProvider cdcProvider;

public SqlServerDriver(..., AppConfig config) {
    ...
    this.cdcProvider = new SqlServerCdcProvider(config.getSource());
}

@Override
public CdcProvider cdcProvider() { return cdcProvider; }
```

- [ ] **Step 5: Update SnapshotStep**

Need to pass `SourceDriver` to SnapshotStep. Update constructor:

```java
// Add field
private final SourceDriver sourceDriver;

// Update constructor
public SnapshotStep(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
    this.config = config;
    this.targetDs = targetDs;
    this.sourceDriver = sourceDriver;
}
```

Replace `new CdcPreChecker(config.getSource())` with `sourceDriver.cdcProvider()`:

```java
// Line 101: old
CdcPreChecker cdcChecker = new CdcPreChecker(config.getSource());
// new
CdcProvider cdcChecker = sourceDriver.cdcProvider();

// Line 142: old
CdcPreChecker cdcChecker = new CdcPreChecker(config.getSource());
// no longer needed — cdcChecker is already created at line 101
```

- [ ] **Step 6: Update SnapshotRunner to pass SourceDriver**

```java
class SnapshotRunner {
    private final AppConfig config;
    private final DataSource targetDs;
    private final SourceDriver sourceDriver;  // new

    SnapshotRunner(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
        this.config = config;
        this.targetDs = targetDs;
        this.sourceDriver = sourceDriver;
    }

    void run(...) {
        // ...
        steps.add(new SnapshotStep(config, targetDs, sourceDriver));  // pass sourceDriver
        // ...
    }
}
```

- [ ] **Step 7: Update App.java to pass SourceDriver to SnapshotRunner**

```java
// line 328
new SnapshotRunner(config, targetDs, driver).run(args, databases, tables);
```

- [ ] **Step 8: Delete CdcPreChecker.java**

```bash
rm src/main/java/com/tool/snapshot/cdc/CdcPreChecker.java
```

- [ ] **Step 9: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: extract CdcProvider interface, move SQL Server impl to SqlServerCdcProvider"
```

---

## Phase 3: SnapshotStep De-inline

### Task 6: Add estimateRowCounts to SchemaExtractor

**Files:**
- Modify: `src/main/java/com/tool/schema/extractor/SchemaExtractor.java`
- Modify: `src/main/java/com/tool/schema/extractor/SqlServerExtractor.java`

- [ ] **Step 1: Add method to interface**

```java
// SchemaExtractor.java — add after listTables()
/**
 * Fast approximate row counts for the given tables.
 * Returns map of {@code "tableName" → estimatedRows}.
 * null or empty return means "unavailable for this source".
 */
default Map<String, Long> estimateRowCounts(Connection conn, List<String[]> tables) throws Exception {
    return Map.of();
}
```

- [ ] **Step 2: Override in SqlServerExtractor**

Move the `estimateRowCounts` method body from `SnapshotStep.java:277-307` into `SqlServerExtractor.java`, changing it from `private` to `@Override public`:

```java
@Override
public Map<String, Long> estimateRowCounts(Connection conn, List<String[]> tables) throws Exception {
    Map<String, Long> estimates = new HashMap<>();
    if (tables.isEmpty()) return estimates;
    var schemaGroups = new HashMap<String, List<String>>();
    for (String[] t : tables) {
        schemaGroups.computeIfAbsent(t[0], k -> new ArrayList<>()).add(t[1]);
    }
    for (var entry : schemaGroups.entrySet()) {
        String schema = entry.getKey();
        List<String> schemaTables = entry.getValue();
        String inClause = schemaTables.stream().map(t -> "?").collect(Collectors.joining(","));
        String sql = "SELECT t.name, SUM(p.row_count) FROM sys.tables t " +
                     "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                     "JOIN sys.dm_db_partition_stats p ON t.object_id = p.object_id " +
                     "WHERE p.index_id IN (0,1) AND s.name=? AND t.name IN (" + inClause + ") " +
                     "GROUP BY t.name";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            for (int i = 0; i < schemaTables.size(); i++) {
                ps.setString(i + 2, schemaTables.get(i));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) estimates.put(rs.getString(1), rs.getLong(2));
            }
        }
    }
    return estimates;
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: add estimateRowCounts() to SchemaExtractor, implement in SqlServerExtractor"
```

---

### Task 7: Update SnapshotStep to use SchemaExtractor (remove inline SQL)

**Files:**
- Modify: `src/main/java/com/tool/snapshot/SnapshotStep.java`

- [ ] **Step 1: Replace discoverDatabases() with SchemaExtractor.listDatabases()**

The `discoverDatabases()` method at line 242 becomes:

```java
// delete the entire method body and replace the call
// In snapshotDatabase(), line 84-88:
List<String> dbNames;
try (Connection master = DriverManager.getConnection(
        sourceDriver.buildJdbcUrl(config.getSource()),
        config.getSource().getUsername(),
        config.getSource().getPassword())) {
    dbNames = sourceDriver.schemaExtractor().listDatabases(master);
}
```

- [ ] **Step 2: Replace discoverTables() with SchemaExtractor.listTables()**

In `snapshotDatabase()`, line 142:

```java
// old
List<String[]> tableList = discoverTables(conn, tables);
// new
List<String[]> tableList = sourceDriver.schemaExtractor().listTables(conn, tables);
```

- [ ] **Step 3: Replace estimateRowCounts() with SchemaExtractor.estimateRowCounts()**

In `snapshotDatabase()`, line 152:

```java
// old
Map<String, Long> estimates = estimateRowCounts(conn, tableList);
// new
Map<String, Long> estimates = sourceDriver.schemaExtractor().estimateRowCounts(conn, tableList);
```

- [ ] **Step 4: Delete the three private methods**

Delete `discoverDatabases()` (line 242-250), `discoverTables()` (line 252-271), and `estimateRowCounts()` (line 277-307).

- [ ] **Step 5: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: remove inline SQL from SnapshotStep, use SchemaExtractor interface"
```

---

## Phase 4: JDBC URL & Debezium Parametrization

### Task 8: Add buildJdbcUrl() to SourceDriver, implement in SqlServerDriver

**Files:**
- Modify: `src/main/java/com/tool/source/SourceDriver.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java`

- [ ] **Step 1: Add methods to SourceDriver**

```java
// Add to SourceDriver interface:
/** Build a JDBC URL to the source server (no specific database). */
String buildJdbcUrl(AppConfig.DbConfig db);

/** Build a JDBC URL targeting a specific database on the source. */
String buildJdbcUrlTo(AppConfig.DbConfig db, String database);
```

- [ ] **Step 2: Implement in SqlServerDriver**

```java
@Override
public String buildJdbcUrl(AppConfig.DbConfig db) {
    return String.format("jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true;loginTimeout=5",
            db.getHost(), db.getPort());
}

@Override
public String buildJdbcUrlTo(AppConfig.DbConfig db, String database) {
    return String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5",
            db.getHost(), db.getPort(), database);
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q && echo "OK"
```

Expected: FAIL — because SourceDriver has new abstract methods but not all implementations provide them. Actually `SqlServerDriver` is the only implementation `@Component`, so it should compile.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: add buildJdbcUrl/buildJdbcUrlTo to SourceDriver"
```

---

### Task 9: Update step constructors to receive SourceDriver, replace jdbcUrl() calls

**Files:**
- Modify: `src/main/java/com/tool/pipeline/steps/PreCheckStep.java`
- Modify: `src/main/java/com/tool/pipeline/steps/DumpStep.java`
- Modify: `src/main/java/com/tool/pipeline/steps/SchemaMigrateStep.java`
- Modify: `src/main/java/com/tool/pipeline/steps/VerifyStep.java`
- Modify: `src/main/java/com/tool/snapshot/SnapshotStep.java`
- Modify: `src/main/java/com/tool/SchemaDumpRunner.java`
- Modify: `src/main/java/com/tool/SnapshotRunner.java`
- Modify: `src/main/java/com/tool/SyncRunner.java`
- Modify: `src/main/java/com/tool/App.java`

- [ ] **Step 1: Update PreCheckStep to use SourceDriver**

Current constructor: `(AppConfig config, ConsistencyProvider consistency)`. Replace with `(AppConfig config, SourceDriver sourceDriver)` — get `consistency` from driver internally.

```java
// Replace field
private final ConsistencyProvider consistency;  // remove
// Add:
private final SourceDriver sourceDriver;

// Replace constructor
public PreCheckStep(AppConfig config, SourceDriver sourceDriver) {
    this.config = config;
    this.sourceDriver = sourceDriver;
}

// Replace line 80 + 97:
config.getSource().jdbcUrl() → sourceDriver.buildJdbcUrl(config.getSource())

// Replace line 78:
consistency.checkPrerequisites(c) → sourceDriver.consistencyProvider().checkPrerequisites(c)
```

- [ ] **Step 2: Add SourceDriver to DumpStep**

```java
// new field
private final SourceDriver sourceDriver;

// updated constructor
public DumpStep(AppConfig config, SchemaExtractor schemaExtractor,
                DumpExtractor dumpExtractor, Supplier<DumpWriter> writerFactory,
                ConsistencyProvider consistency, SourceDriver sourceDriver) {
    // ... existing assignments ...
    this.sourceDriver = sourceDriver;
}

// replace line 96:
config.getSource().jdbcUrlTo(dbName) → sourceDriver.buildJdbcUrlTo(config.getSource(), dbName)
```

- [ ] **Step 3: Add SourceDriver to SchemaMigrateStep**

```java
// new field
private final SourceDriver sourceDriver;

// updated constructor
public SchemaMigrateStep(AppConfig config, SchemaExtractor extractor,
                         SchemaConverter converter, SchemaWriter writer,
                         ProgressReporter progress, SourceDriver sourceDriver) {
    // ... existing assignments ...
    this.sourceDriver = sourceDriver;
}

// replace line 91:
config.getSource().jdbcUrl() → sourceDriver.buildJdbcUrl(config.getSource())
// replace line 109:
config.getSource().jdbcUrlTo(dbName) → sourceDriver.buildJdbcUrlTo(config.getSource(), dbName)
```

- [ ] **Step 4: Add SourceDriver to VerifyStep**

```java
// new field
private final SourceDriver sourceDriver;

// updated constructor
public VerifyStep(AppConfig config, SchemaVerifier verifier, SourceDriver sourceDriver) {
    this.config = config;
    this.verifier = verifier;
    this.sourceDriver = sourceDriver;
}

// replace line 78:
config.getSource().jdbcUrlTo(db.dbName()) → sourceDriver.buildJdbcUrlTo(config.getSource(), db.dbName())
```

- [ ] **Step 5: SnapshotStep already has sourceDriver from Task 5**

Replace remaining `config.getSource().jdbcUrl()` and `config.getSource().jdbcUrlTo()` calls with `sourceDriver.buildJdbcUrl()` and `sourceDriver.buildJdbcUrlTo()`.

- [ ] **Step 6: Update SchemaDumpRunner to pass SourceDriver**

SchemaDumpRunner already has `driver` field (SourceDriver). Update step construction:

```java
// PreCheckStep line 69:
steps.add(new PreCheckStep(config, driver));
// was: new PreCheckStep(config, driver.consistencyProvider())

// DumpStep line 88-89:
steps.add(new DumpStep(config, extractor, driver.dumpExtractor(),
        () -> new CsvDumpWriter(threshold), driver.consistencyProvider(), driver));

// SchemaMigrateStep line 92:
steps.add(new SchemaMigrateStep(config, extractor, converter, writer, progress, driver));

// VerifyStep line 93:
steps.add(new VerifyStep(config, verifier, driver));
```

- [ ] **Step 7: Update SnapshotRunner**

```java
steps.add(new PreCheckStep(config, sourceDriver));
steps.add(new SnapshotStep(config, targetDs, sourceDriver));
```

- [ ] **Step 8: Update SyncRunner**

Add `SourceDriver` field and constructor param, then:

```java
// field
private final SourceDriver sourceDriver;

// constructor
SyncRunner(AppConfig config, DataSource targetDs, SourceDriver sourceDriver) {
    this.config = config;
    this.targetDs = targetDs;
    this.sourceDriver = sourceDriver;
}

// In run():
steps.add(new PreCheckStep(config, sourceDriver));
steps.add(new SyncStep(config, targetDs, sourceDriver));
```

- [ ] **Step 9: Update App.java dispatchers**

```java
// line 328 (snapshot):
new SnapshotRunner(config, targetDs, driver).run(args, databases, tables);

// line 338 (sync):
new SyncRunner(config, targetDs, driver).run(args);

// line 358-359 (schema/dump):
new SchemaDumpRunner(config, extractor, converter, writer, verifier, driver)
        .run(args, mode, databases, tables, dryRun, dropIfExists, continueOnError);
```

- [ ] **Step 9: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: inject SourceDriver into steps, replace DbConfig.jdbcUrl() calls"
```

---

### Task 10: Remove jdbcUrl()/jdbcUrlTo() from DbConfig

**Files:**
- Modify: `src/main/java/com/tool/config/AppConfig.java`
- Modify: `src/main/java/com/tool/loadgen/LoadGenerator.java`

- [ ] **Step 1: Delete methods**

Remove `jdbcUrl()` and `jdbcUrlTo()` from `AppConfig.DbConfig` (lines 33-39). Keep `tidbJdbcUrl()`.

- [ ] **Step 2: Fix LoadGenerator**

LoadGenerator is a dev utility. Replace `sourceConfig.jdbcUrlTo(database)` with inline SQL Server URL:

```java
String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5",
        sourceConfig.getHost(), sourceConfig.getPort(), database)
```

(This is acceptable — LoadGenerator is already SQL Server-only, not part of the abstraction.)

- [ ] **Step 3: Compile to verify no other callers**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove DbConfig.jdbcUrl()/jdbcUrlTo(), delegate to SourceDriver"
```

---

### Task 11: Fix DumpStep hardcoded snapshot URL

**Files:**
- Modify: `src/main/java/com/tool/pipeline/steps/DumpStep.java:354-357`

- [ ] **Step 1: Replace hardcoded URL**

In `dumpPkRange()`, line 354-357:

```java
// old
String snapDbName = snapMap.get(dbName);
String snapUrl = String.format(
        "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5",
        config.getSource().getHost(), config.getSource().getPort(), snapDbName);

// new
String snapDbName = snapMap.get(dbName);
String snapUrl = consistency.jdbcUrlForSnapshot(dbName, snapDbName);
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tool/pipeline/steps/DumpStep.java
git commit -m "fix: use consistency.jdbcUrlForSnapshot() in dumpPkRange instead of hardcoded URL"
```

---

### Task 12: Add getDebeziumConnectorClass() to SourceDriver, update factories

**Files:**
- Modify: `src/main/java/com/tool/source/SourceDriver.java`
- Modify: `src/main/java/com/tool/source/SqlServerDriver.java`
- Modify: `src/main/java/com/tool/snapshot/engine/DebeziumEngineFactory.java`
- Modify: `src/main/java/com/tool/sync/SyncEngineFactory.java`

- [ ] **Step 1: Add method to SourceDriver**

```java
/** Fully qualified Debezium connector class name for this source. */
String debeziumConnectorClass();
```

- [ ] **Step 2: Implement in SqlServerDriver**

```java
@Override
public String debeziumConnectorClass() {
    return "io.debezium.connector.sqlserver.SqlServerConnector";
}
```

- [ ] **Step 3: Update DebeziumEngineFactory**

Add `debeziumConnectorClass` parameter to constructor and use it:

```java
public class DebeziumEngineFactory {
    private final AppConfig.DbConfig source;
    private final String connectorClass;

    public DebeziumEngineFactory(AppConfig.DbConfig source, String connectorClass) {
        this.source = source;
        this.connectorClass = connectorClass;
    }

    // in create():
    props.setProperty("connector.class", connectorClass);
    // was: props.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
}
```

- [ ] **Step 4: Update SyncEngineFactory (same pattern)**

```java
public class SyncEngineFactory {
    private final AppConfig.DbConfig source;
    private final String connectorClass;

    public SyncEngineFactory(AppConfig.DbConfig source, String connectorClass) {
        this.source = source;
        this.connectorClass = connectorClass;
    }

    // in create():
    props.setProperty("connector.class", connectorClass);
}
```

- [ ] **Step 5: Update callers to pass connector class**

In `SnapshotStep.snapshotDatabase()` line 163:
```java
DebeziumEngineFactory factory = new DebeziumEngineFactory(
        config.getSource(), sourceDriver.debeziumConnectorClass());
```

In `SyncStep.execute()` line 109:
```java
SyncEngineFactory engineFactory = new SyncEngineFactory(
        config.getSource(), sourceDriver.debeziumConnectorClass());
```

In `SyncStep.ensureSchemaHistory()` line 184:
```java
SyncEngineFactory factory = new SyncEngineFactory(
        config.getSource(), sourceDriver.debeziumConnectorClass());
```

SyncStep already receives `sourceDriver` via its constructor (added in Task 9 Step 8).

- [ ] **Step 6: Compile**

```bash
mvn compile -q && echo "OK"
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: parametrize Debezium connector class via SourceDriver"
```

---

## Verification

```bash
mvn test -q && echo "ALL TESTS PASS"
```

Run each mode once to verify no regression:

```bash
# schema
java -jar dist/any2tidb.jar sqlserver schema --databases=HRDB --dry-run

# dump
java -jar dist/any2tidb.jar sqlserver dump --databases=HRDB --tables=employees

# snapshot
java -jar dist/any2tidb.jar sqlserver snapshot --databases=HRDB --tables=employees
```
