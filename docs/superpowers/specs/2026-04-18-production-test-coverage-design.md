# Production-Grade Test Coverage Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring ms2tidb's test suite to production-grade robustness by systematically closing all coverage gaps across unit and integration layers — including edge-case inputs, business exceptions, and TiDB-side error scenarios.

**Architecture:** All tests use real databases (SQL Server + TiDB) for integration/E2E layers; no mock framework introduced. Tests are distributed across 5 existing test files, each with a clear responsibility. No new test classes are created.

**Tech Stack:** JUnit 5, JDBC, SQL Server 2019+, TiDB v7.x, Maven Surefire + integration profile (`-Pintegration`)

---

## Responsibility Map

| File | Layer | Responsibility |
|------|-------|---------------|
| `TypeMapperTest` | Unit | Every SQL Server type → TiDB type mapping, including all edge inputs |
| `SchemaConverterTest` | Unit | DDL string generation: defaults, comments, constraints, error paths |
| `DdlExecutionTest` | TiDB-only integration | Execute converter output against live TiDB; verify table structure and safety logic |
| `IntegrationTest` | SS→TiDB E2E | Full pipeline: extract from SQL Server, convert, execute on TiDB |
| `VerifyIntegrationTest` | SS+TiDB E2E | SchemaVerifier accuracy across all mismatch dimensions |

---

## Section 1 — TypeMapperTest (Unit)

### 1a. Missing type coverage

Add one `@Test` per type, following existing one-liner style:

| Test name | Input | Expected tidbType | Warning? |
|-----------|-------|-------------------|---------|
| `sysname_mapsToVarchar128` | `sysname` | `VARCHAR(128)` | no |
| `rowversion_mapsToBigintUnsigned` | `rowversion` | `BIGINT UNSIGNED` | yes, contains "ROWVERSION" |
| `hierarchyid_mapsToVarchar4000` | `hierarchyid` | `VARCHAR(4000)` | yes |
| `sql_variant_mapsToLongtext` | `sql_variant` | `LONGTEXT` | yes |
| `vector_mapsToVector` | `vector` | `VECTOR` | yes, contains "v8.4" |
| `geography_mapsToGeometryWithWarning` | `geography` | `GEOMETRY` | yes |
| `cursor_isSkipped` | `cursor` | skip=true | — |
| `table_isSkipped` | `table` | skip=true | — |

### 1b. Edge-case inputs (boundary values)

| Test name | Scenario | Assert |
|-----------|----------|--------|
| `decimal_zeroPrecision_defaultsTo18` | `decimal`, precision=0, scale=0 | `DECIMAL(18,0)` |
| `decimal_maxPrecision` | `decimal`, precision=38, scale=10 | `DECIMAL(38,10)` |
| `datetime2_scaleZero_isDatetime` | `datetime2`, scale=0 | `DATETIME` (no fsp suffix) |
| `datetime2_scaleNull_defaultsTo6` | `datetime2`, scale=null | `DATETIME(6)`, no warning |
| `datetime2_scale6_noWarning` | `datetime2`, scale=6 | `DATETIME(6)`, no warning |
| `datetime2_scale7_truncatedTo6` | `datetime2`, scale=7 | `DATETIME(6)`, warning contains "truncated" |
| `nvarchar_maxLengthOne_isVarchar1` | `nvarchar`, maxLength=2 (1 char) | `VARCHAR(1) CHARACTER SET utf8mb4` |
| `varchar_maxLengthZero_treatedAsNull` | `varchar`, maxLength=0 | handled gracefully (no NPE) |
| `char_nullLength_defaultsToOne` | `char`, maxLength=null | `CHAR(1)` |
| `nchar_nullLength_defaultsToOne` | `nchar`, maxLength=null | `CHAR(1) CHARACTER SET utf8mb4` |
| `unknownType_isSkipped` | `__totally_unknown__` | skip=true, warningMessage contains "Unknown type" |
| `typeNameWithWhitespace_isTrimmed` | `" int "` (spaces) | `INT`, no skip |
| `typeNameUppercase_isCaseInsensitive` | `"BIGINT"` | `BIGINT` |

### 1c. Default value mapping (TypeMapper.mapDefaultValue)

| Test name | Input | Expected output |
|-----------|-------|----------------|
| `defaultValue_null_returnsNull` | null | null |
| `defaultValue_getdate_mapsToCurrentTimestamp` | `((getdate()))` | `CURRENT_TIMESTAMP` |
| `defaultValue_getutcdate_mapsToUtcTimestamp` | `(getutcdate())` | `UTC_TIMESTAMP()` |
| `defaultValue_newid_mapsToUuid` | `((newid()))` | `UUID()` |
| `defaultValue_newsequentialid_mapsToUuid` | `(newsequentialid())` | `UUID()` |
| `defaultValue_sysdatetime_mapsToCurrent6` | `(sysdatetime())` | `CURRENT_TIMESTAMP(6)` |
| `defaultValue_numericLiteral_passThrough` | `((0))` | `0` |
| `defaultValue_stringLiteral_stripParens` | `('active')` | `'active'` |
| `defaultValue_deeplyNested_stripsAllParens` | `(((42)))` | `42` |

---

## Section 2 — SchemaConverterTest (Unit)

### 2a. DDL content coverage

| Test name | Scenario | Assert |
|-----------|----------|--------|
| `defaultValue_getdateIsTranslated` | `datetime` col with `defaultValue="((getdate()))"` | DDL contains `DEFAULT CURRENT_TIMESTAMP`; warning contains "translated" |
| `defaultValue_numericLiteral_noWarning` | `int` col with `defaultValue="((0))"` | DDL contains `DEFAULT 0`; no warning |
| `columnComment_isIncludedInDDL` | `name` col with `comment="用户姓名"` | DDL contains `COMMENT '用户姓名'` |
| `columnComment_withSingleQuote_isEscaped` | comment = `O'Brien's field` | DDL contains `COMMENT 'O''Brien''s field'` |
| `uniqueConstraint_generatesUniqueKey` | `uniqueConstraints = {"UQ_email": "email"}` | DDL contains `UNIQUE KEY \`UQ_email\`` |
| `multiColumnUniqueConstraint` | `uniqueConstraints = {"UQ_tc": "tenant_id,code"}` | DDL contains both column names quoted |
| `noPrimaryKey_stillValidDDL` | empty `primaryKeyColumns` | DDL does not contain `PRIMARY KEY`; no error |
| `identityColumn_noDefaultApplied` | identity=true + defaultValue set | `AUTO_INCREMENT` present; no `DEFAULT` clause for that column |

### 2b. Error and warning paths

| Test name | Scenario | Assert |
|-----------|----------|--------|
| `unskippableColumn_errorsWholeTable` | cursor column | returns null; status=ERROR |
| `multipleUnskippableColumns_allListed` | two cursor+table columns | error message lists both column names |
| `onlySkipColumnInTable_errors` | table with only one cursor column | null DDL, status=ERROR |
| `partitionedTable_addsWarning` | `isPartitioned=true` | warning contains "partitioned" |
| `foreignKey_addsWarning` | `foreignKeyCount=3` | warning contains "foreign key" |
| `checkConstraint_isDiscarded` | one CHECK constraint | no CHECK in DDL; warning contains "CHECK" |
| `multipleCheckConstraints_countInWarning` | three CHECK constraints | warning mentions "3" |
| `fulltextIndex_isDiscarded` | fulltext=true index | index name absent from DDL; warning contains "FULLTEXT" |
| `columnstoreIndex_isDiscarded` | columnstore=true index | warning contains "COLUMNSTORE" |
| `filteredIndex_whereDropped` | filterDefinition set | index present; warning contains "WHERE filter" |
| `clusteredIndex_convertedToRegular` | clustered=true index | index present; warning contains "CLUSTERED" |
| `includeColumns_dropped` | includeColumns non-empty | index present; warning contains "INCLUDE" |
| `computedColumn_addsWarningAndConvertsToPlainColumn` | computed=true column | column appears; warning contains "computed column" |
| `emptyIndexColumns_indexSkipped` | index with empty columns list | no DDL for that index; no error |
| `dropIfExists_prependsDropStatement` | dropIfExists=true | DDL starts with `DROP TABLE IF EXISTS` |

---

## Section 3 — DdlExecutionTest (TiDB Integration)

All tests in this file: build `TableSchema` objects directly (no SQL Server), execute on live TiDB, verify via JDBC metadata or query results.

### 3a. Existing tests (keep, already passing)
DE01–DE11: integers, decimal, float, datetime, strings, binary, special types, identity, indexes, dropIfExists, CHECK discard.

### 3b. New: TiDBWriter safety logic

| Test | Scenario | Assert |
|------|----------|--------|
| `de12_dropIfExists_skippedWhenTableHasData` | Create table → insert row → run with dropIfExists=true | warning contains "already contains data"; original row still exists; status≠ERROR |
| `de13_dropIfExists_allowedWhenTableEmpty` | Create table → no rows → run dropIfExists=true | table recreated successfully; status≠ERROR |
| `de14_multiStatementDDL_allStatementsExecuted` | Table with two indexes (DDL = CREATE TABLE + 2× CREATE INDEX) | all three statements execute; both index names found in metadata |

### 3c. New: index variant coverage

| Test | Scenario | Assert |
|------|----------|--------|
| `de15_filteredIndex_whereDropped_indexSurvives` | IndexSchema with filterDefinition set | index exists in TiDB; warning contains "WHERE filter" |
| `de16_columnstoreIndex_discarded` | IndexSchema with columnstore=true | index absent from TiDB; warning contains "COLUMNSTORE" |
| `de17_computedColumn_createsPlainColumn` | ColumnSchema with computed=true | column exists in TiDB metadata; warning contains "computed column" |
| `de18_clusteredIndex_convertedToRegular` | IndexSchema with clustered=true | index exists; warning contains "CLUSTERED" |
| `de19_includeColumns_droppedIndexSurvives` | IndexSchema with includeColumns non-empty | index exists; warning contains "INCLUDE" |

### 3d. New: unskippable column — table-level error

| Test | Scenario | Assert |
|------|----------|--------|
| `de20_unskippableColumn_ddlIsNull_tableNotCreated` | Table with cursor column | `toCreateTableDDL` returns null; table absent from TiDB; status=ERROR |

### 3e. New: edge-case DDL execution

| Test | Scenario | Assert |
|------|----------|--------|
| `de21_tableWithNoIndexes_createsCleanly` | Minimal table, no indexes | table exists; status=OK |
| `de22_tableWith30Columns_createsCleanly` | 30 mixed-type columns | table exists; JDBC column count=30 |
| `de23_columnNameWithBacktick_isSafe` | column name = `value` (reserved word) | table creates without error (backtick-quoting works) |
| `de24_veryLongTableName_createsCleanly` | table name = 60-char string | table creates; tableExists() returns true |
| `de25_defaultValueUuid_isValid` | uniqueidentifier col with `NEWID()` default | DDL executes; INSERT without explicit guid succeeds |

---

## Section 4 — IntegrationTest (SS→TiDB E2E)

All tests: create table on SQL Server, extract via `SqlServerExtractor`, convert, execute on TiDB.

### 4a. Existing tests (keep, already passing)
TC01–TC12.

### 4b. New: column/index types missing from E2E

| Test | SS DDL | Assert |
|------|--------|--------|
| `tc13_computedColumn` | col `AS (price * 1.1)` PERSISTED | TiDB column exists; warning contains "computed column" |
| `tc14_filteredIndex` | `CREATE INDEX IX_active ON t (status) WHERE status = 1` | index present in TiDB; warning contains "WHERE filter" |
| `tc15_columnstoreIndex` | `CREATE COLUMNSTORE INDEX CCI ON t (...)` | index absent from TiDB; warning contains "COLUMNSTORE" |
| `tc16_multiSchemaConflictDetection` | (App-level test note — handled in App.java, not extractable via IntegrationTest alone; document as out of scope for this file) | — |

### 4c. New: default value E2E

| Test | SS DDL | Assert |
|------|--------|--------|
| `tc17_defaultGetdate_translatedAndExecutes` | col `created_at DATETIME DEFAULT GETDATE()` | DDL executes; warning contains "translated"; TiDB column has `CURRENT_TIMESTAMP` default |
| `tc18_defaultNumericLiteral_passThrough` | col `status INT DEFAULT 0` | DDL executes; no warning for this col; TiDB column default = '0' |
| `tc19_defaultStringLiteral_passThrough` | col `country CHAR(2) DEFAULT 'CN'` | DDL executes; TiDB column default = 'CN' (quotes stripped) |

### 4d. New: data integrity after migration

| Test | Scenario | Assert |
|------|----------|--------|
| `tc20_notNullEnforced` | col `name VARCHAR(100) NOT NULL` migrated | INSERT without name fails with SQLException |
| `tc21_uniqueConstraintEnforced` | UNIQUE constraint migrated | duplicate INSERT fails with SQLException |
| `tc22_insertAndSelectRoundtrip` | migrate table, insert row with all basic types | SELECT returns same values |

### 4e. New: business exception paths

| Test | Scenario | Assert |
|------|----------|--------|
| `tc23_tableWithOnlyCursorColumn_isSkipped` | SS table with `cursor` typed column | `toCreateTableDDL` returns null; status=ERROR; table not created in TiDB |
| `tc24_tableWithMixedSkippableAndNormal_isSkipped` | cursor col + normal cols | entire table skipped; status=ERROR |
| `tc25_tidbDdlError_setsErrorStatus` | Generate DDL with duplicate column names (manually corrupt `TableSchema`) | `executeDDL` catches SQLException; status=ERROR; errorMessage contains SQL state |

---

## Section 5 — VerifyIntegrationTest (Verifier E2E)

### 5a. Existing tests (keep)
VT01–VT04.

### 5b. New: per-dimension mismatch verification

| Test | Setup | Mutate TiDB | Assert |
|------|-------|-------------|--------|
| `vt05_notNullMismatch` | 3 NOT NULL cols migrated | `ALTER TABLE … MODIFY col … NULL` | `isMismatch()=true`; diffLines contains "notnull mismatch" |
| `vt06_autoIncrementMismatch` | IDENTITY col migrated | `ALTER TABLE … MODIFY id INT NOT NULL` (remove AUTO_INCREMENT) | `isMismatch()=true`; diffLines contains "ai mismatch" |
| `vt07_defaultValueMismatch` | col with DEFAULT 0 migrated | `ALTER TABLE … ALTER COLUMN … SET DEFAULT 1` | `isMismatch()=true`; diffLines contains "default[" |
| `vt08_indexCountMismatch` | 2 indexes migrated | `DROP INDEX IX_x ON t` | `isMismatch()=true`; diffLines contains "idx mismatch" |
| `vt09_colOrderMismatch` | 3-col table migrated | (can't ALTER ORDER in place — rebuild table in wrong order) | `isMismatch()=true`; diffLines contains "col order mismatch" |
| `vt10_pkMismatch` | composite PK migrated | rebuild TiDB table with single-col PK | `isMismatch()=true`; diffLines contains "pk mismatch" |
| `vt11_extraColInTidb` | 2-col table migrated | `ALTER TABLE … ADD COLUMN extra INT` | `isMismatch()=true`; diffLines contains "extra cols" |
| `vt12_defaultWithQuotes_notMismatch` | col `DEFAULT 'CN'` migrated | (no mutation) | `isMismatch()=false` — quote-stripping normalisation holds |
| `vt13_checkConstraintDiff_notMismatch` | CHECK constraint in SS, discarded in TiDB | (no mutation) | `isMismatch()=false` — CHECK not tracked |
| `vt14_verifyAll_mixedResults` | 3 tables migrated; manually break one | verifyAll on all 3 | results has 1 mismatch, 2 OK |

### 5c. New: verify on edge-case table shapes

| Test | Scenario | Assert |
|------|----------|--------|
| `vt15_tableWithNoIndexes_isOK` | table with only PK | `isMismatch()=false`; msIdx=0; tidbIdx=0 |
| `vt16_tableWithNoAiCol_isOK` | table with no IDENTITY column | `isMismatch()=false`; msAiCol=null; tidbAiCol=null |
| `vt17_tableWithCompositeAi_isOK` | (impossible in SS but verify graceful handling) | no exception thrown |

---

## Test Count Summary

| File | Current | New | Total |
|------|---------|-----|-------|
| `TypeMapperTest` | 22 | +22 | ~44 |
| `SchemaConverterTest` | 8 | +15 | ~23 |
| `DdlExecutionTest` | 11 | +14 | ~25 |
| `IntegrationTest` | 12 | +13 | ~25 |
| `VerifyIntegrationTest` | 4 | +13 | ~17 |
| **Total** | **57** | **+77** | **~134** |

---

## Scope Boundaries (explicitly out of scope)

- `App.java` main flow (table-name conflict detection, continueOnError, --tables arg): these require a full Spring Boot context or process-level testing; deferred to a separate CLI smoke-test spec.
- JaCoCo coverage reporting: not required — real-database integration tests give stronger signal than line coverage for this tool.
- Mock-based testing: explicitly rejected in favour of real-database integration tests.
- TiDB v8.x geometry/vector tests: environment is v7.x; separate spec when environment upgrades.
