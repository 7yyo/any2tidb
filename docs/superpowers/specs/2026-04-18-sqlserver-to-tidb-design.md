# SQL Server to TiDB Schema Migration Tool — Design Spec

**Date:** 2026-04-18  
**Status:** Approved

---

## Overview

A Java-based CLI tool that connects to a SQL Server instance, reads table schemas, converts them to TiDB-compatible DDL, and executes the DDL on a target TiDB instance.

---

## Architecture

```
sqlserver-to-tidb-tool/
├── src/main/java/com/tool/
│   ├── App.java                        # Entry point, CLI argument parsing
│   ├── config/
│   │   └── DataSourceConfig.java       # Dual datasource configuration
│   ├── extractor/
│   │   └── SqlServerExtractor.java     # Read schema from SQL Server via JDBC
│   ├── converter/
│   │   └── SchemaConverter.java        # Type/syntax conversion logic
│   ├── writer/
│   │   └── TiDBWriter.java             # Execute DDL on TiDB via JDBC
│   └── model/
│       └── TableSchema.java            # Intermediate data model
├── src/main/resources/
│   └── application.yml                 # Database connection config
└── pom.xml
```

**Data flow:**
```
SQL Server --[JDBC]--> Extractor --> TableSchema --> Converter --> TiDBWriter --[JDBC]--> TiDB
```

`TableSchema` is the central intermediate model that decouples extraction from writing. All conversion logic is isolated in `SchemaConverter`.

---

## Technology Stack

- **Language:** Java (Spring Boot CLI)
- **Build:** Maven
- **SQL Server driver:** `mssql-jdbc`
- **TiDB driver:** `mysql-connector-java` (TiDB is MySQL-compatible)
- **CLI parsing:** Spring Boot `ApplicationRunner` + args

---

## Data Type Mapping

### Exact Numerics

| SQL Server | TiDB | Notes |
|---|---|---|
| `INT` / `INTEGER` | `INT` | Direct mapping |
| `BIGINT` | `BIGINT` | Direct mapping |
| `SMALLINT` | `SMALLINT` | Direct mapping |
| `TINYINT` | `TINYINT UNSIGNED` | SS range is 0-255, use UNSIGNED |
| `BIT` | `TINYINT(1)` | No boolean BIT in TiDB |
| `DECIMAL(p,s)` / `NUMERIC(p,s)` | `DECIMAL(p,s)` | Direct mapping |
| `MONEY` | `DECIMAL(19,4)` | ⚠ WARN |
| `SMALLMONEY` | `DECIMAL(10,4)` | ⚠ WARN |

### Approximate Numerics

| SQL Server | TiDB | Notes |
|---|---|---|
| `FLOAT` | `DOUBLE` | Direct mapping |
| `REAL` | `FLOAT` | Direct mapping |

### Date and Time

| SQL Server | TiDB | Notes |
|---|---|---|
| `DATE` | `DATE` | Direct mapping |
| `TIME` | `TIME` | Direct mapping |
| `DATETIME` | `DATETIME` | Direct mapping |
| `DATETIME2` | `DATETIME` | Precision may be lost ⚠ WARN |
| `SMALLDATETIME` | `DATETIME` | Direct mapping |
| `DATETIMEOFFSET` | `DATETIME` | Timezone info lost ⚠ WARN |

### Character Strings

| SQL Server | TiDB | Notes |
|---|---|---|
| `CHAR(n)` | `CHAR(n)` | Direct mapping |
| `VARCHAR(n)` | `VARCHAR(n)` | Direct mapping |
| `VARCHAR(MAX)` | `LONGTEXT` | ⚠ WARN |
| `TEXT` | `LONGTEXT` | ⚠ WARN |
| `NCHAR(n)` | `CHAR(n) CHARACTER SET utf8mb4` | Strip N prefix |
| `NVARCHAR(n)` | `VARCHAR(n) CHARACTER SET utf8mb4` | Strip N prefix |
| `NVARCHAR(MAX)` | `LONGTEXT` | ⚠ WARN |
| `NTEXT` | `LONGTEXT` | ⚠ WARN |

### Binary Strings

| SQL Server | TiDB | Notes |
|---|---|---|
| `BINARY(n)` | `BINARY(n)` | Direct mapping |
| `VARBINARY(n)` | `VARBINARY(n)` | Direct mapping |
| `VARBINARY(MAX)` | `LONGBLOB` | ⚠ WARN |
| `IMAGE` | `LONGBLOB` | Deprecated type ⚠ WARN |

### Other Types

| SQL Server | TiDB | Notes |
|---|---|---|
| `UNIQUEIDENTIFIER` | `VARCHAR(36)` | ⚠ WARN |
| `ROWVERSION` / `TIMESTAMP` | `BIGINT UNSIGNED` | Row version, not datetime ⚠ WARN |
| `XML` | `LONGTEXT` | ⚠ WARN |
| `JSON` | `JSON` | Direct mapping |
| `VECTOR` | `VECTOR` | Requires TiDB v8.4+ ⚠ WARN |
| `HIERARCHYID` | `VARCHAR(4000)` | ⚠ WARN |
| `GEOGRAPHY` | `GEOMETRY` | ⚠ WARN |
| `GEOMETRY` | `GEOMETRY` | Direct mapping |
| `SQL_VARIANT` | `LONGTEXT` | ⚠ WARN |
| `SYSNAME` | `VARCHAR(128)` | System internal type |
| `CURSOR` | SKIP | Not applicable to table columns ⚠ WARN |
| `TABLE` | SKIP | Variable type, not applicable ⚠ WARN |
| Unknown types | SKIP column | ⚠ WARN |

### Default Value Function Mapping

| SQL Server | TiDB |
|---|---|
| `GETDATE()` | `CURRENT_TIMESTAMP` |
| `GETUTCDATE()` | `UTC_TIMESTAMP()` |
| `NEWID()` | `UUID()` |
| `NEWSEQUENTIALID()` | `UUID()` ⚠ WARN |
| `SYSDATETIME()` | `CURRENT_TIMESTAMP(6)` |

---

## Constraints & Indexes

### Primary Key
- Direct mapping
- `CLUSTERED` / `NONCLUSTERED` keywords stripped (TiDB defaults to clustered PK)

### Unique Constraint
- Direct mapping
- `CLUSTERED` / `NONCLUSTERED` keywords stripped

### Check Constraint
- Direct mapping
- ⚠ WARN: remind user that CHECK constraints require TiDB 8.0+

### Default Values
- Literal defaults mapped directly
- Function defaults mapped per the table above

### Foreign Keys
- **Discarded entirely**, ⚠ WARN logged per FK dropped

### Indexes

| SQL Server | TiDB | Notes |
|---|---|---|
| `CREATE INDEX` | `CREATE INDEX` | Direct mapping |
| `CREATE UNIQUE INDEX` | `CREATE UNIQUE INDEX` | Direct mapping |
| `CLUSTERED INDEX` (non-PK) | `INDEX` | ⚠ WARN: clustered hint dropped |
| `NONCLUSTERED INDEX` | `INDEX` | Strip NONCLUSTERED |
| `INCLUDE (col)` | Strip INCLUDE | TiDB doesn't support covering index INCLUDE ⚠ WARN |
| `FULLTEXT INDEX` | Discard | Not supported ⚠ WARN |
| `COLUMNSTORE INDEX` | Discard | Not supported ⚠ WARN |
| Filtered index `WHERE` clause | Strip WHERE, keep index | TiDB doesn't support filtered indexes ⚠ WARN |
| Storage options (`PAD_INDEX`, `FILLFACTOR`, etc.) | Discard | Not applicable |

---

## Partitioned Tables

Partitioned tables are converted to regular tables. A warning is logged for each partitioned table encountered. Users are responsible for re-adding partitions manually if needed.

---

## CLI Design

### Configuration File (`application.yml`)

```yaml
sqlserver:
  host: 192.168.1.100
  port: 1433
  database: mydb
  username: sa
  password: xxx

tidb:
  host: 192.168.1.200
  port: 4000
  database: mydb
  username: root
  password: xxx

convert:
  schemas:            # Schemas to convert; empty = all schemas
    - dbo
  tables:             # Tables to convert; empty = all tables
    - orders
    - products
  drop-if-exists: false    # DROP TABLE before CREATE
  continue-on-error: true  # Continue on single table failure
```

### Commands

```bash
# Use default config file
java -jar sqlserver-to-tidb.jar

# Specify config file
java -jar sqlserver-to-tidb.jar --config /path/to/config.yml

# Convert specific tables only
java -jar sqlserver-to-tidb.jar --tables orders,products

# Dry run: print DDL without executing
java -jar sqlserver-to-tidb.jar --dry-run
```

### Log Output Format

```
[INFO]  Starting conversion, found 42 tables
[INFO]  [1/42] Converting table dbo.orders ... OK
[WARN]  [2/42] Converting table dbo.products ... column 'img' type IMAGE converted to LONGBLOB
[WARN]  [3/42] Converting table dbo.logs ... partitioned table detected, converted to regular table
[ERROR] [4/42] Converting table dbo.events ... failed to execute DDL: <error message>
[INFO]  Conversion completed: 39 succeeded, 2 warnings, 1 failed
```

---

## Error Handling

- Unknown/unsupported types: skip column, log `[WARN]`
- Unsupported index/constraint features: strip feature, log `[WARN]`
- DDL execution failure on TiDB: log `[ERROR]`, continue if `continue-on-error: true`, else abort
- Connection failure: always abort with `[ERROR]`

---

## Out of Scope

- Views, stored procedures, triggers, functions
- User-defined types (UDT)
- Data migration (rows)
- SQL Server Agent jobs, logins, permissions
