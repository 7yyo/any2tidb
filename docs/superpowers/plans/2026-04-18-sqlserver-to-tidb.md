# SQL Server to TiDB Migration Tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot CLI tool that reads SQL Server table schemas via JDBC and recreates them on TiDB, handling type mapping, constraint conversion, and index translation.

**Architecture:** The tool connects to both databases simultaneously. `SqlServerExtractor` reads raw schema metadata into `TableSchema` model objects. `SchemaConverter` transforms those models into TiDB-compatible DDL strings. `TiDBWriter` executes the DDL. All conversion warnings and errors are printed to stdout using a structured log format.

**Tech Stack:** Java 17, Spring Boot 3.x, Maven, `mssql-jdbc:12.x`, `mysql-connector-java:8.x`, `snakeyaml` (bundled with Spring Boot)

---

## File Map

| File | Purpose |
|---|---|
| `pom.xml` | Maven build, dependency management |
| `src/main/resources/application.yml` | Default config template |
| `src/main/java/com/tool/App.java` | Entry point, CLI arg parsing, orchestration |
| `src/main/java/com/tool/config/AppConfig.java` | POJO for YAML config binding |
| `src/main/java/com/tool/model/ColumnSchema.java` | Column metadata model |
| `src/main/java/com/tool/model/IndexSchema.java` | Index metadata model |
| `src/main/java/com/tool/model/TableSchema.java` | Table metadata model (contains columns + indexes) |
| `src/main/java/com/tool/extractor/SqlServerExtractor.java` | Reads schema from SQL Server via `sys.*` tables |
| `src/main/java/com/tool/converter/TypeMapper.java` | SQL Server → TiDB type mapping |
| `src/main/java/com/tool/converter/SchemaConverter.java` | Builds full CREATE TABLE DDL from TableSchema |
| `src/main/java/com/tool/writer/TiDBWriter.java` | Executes DDL on TiDB |
| `src/main/java/com/tool/ConversionResult.java` | Result record per table (OK/WARN/ERROR + messages) |
| `src/test/java/com/tool/converter/TypeMapperTest.java` | Unit tests for type mapping |
| `src/test/java/com/tool/converter/SchemaConverterTest.java` | Unit tests for DDL generation |

---

## Task 1: Maven Project Scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.tool</groupId>
    <artifactId>sqlserver-to-tidb</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
    </parent>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.microsoft.sqlserver</groupId>
            <artifactId>mssql-jdbc</artifactId>
            <version>12.6.1.jre11</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.3.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `src/main/resources/application.yml`**

```yaml
sqlserver:
  host: 127.0.0.1
  port: 1433
  database: mydb
  username: sa
  password: yourpassword

tidb:
  host: 127.0.0.1
  port: 4000
  database: mydb
  username: root
  password: ""

convert:
  schemas:
  tables:
  drop-if-exists: false
  continue-on-error: true
```

- [ ] **Step 3: Verify project compiles**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "chore: scaffold Maven project with Spring Boot and JDBC dependencies"
```

---

## Task 2: Config and Model Classes

**Files:**
- Create: `src/main/java/com/tool/config/AppConfig.java`
- Create: `src/main/java/com/tool/model/ColumnSchema.java`
- Create: `src/main/java/com/tool/model/IndexSchema.java`
- Create: `src/main/java/com/tool/model/TableSchema.java`
- Create: `src/main/java/com/tool/ConversionResult.java`

- [ ] **Step 1: Create `AppConfig.java`**

```java
package com.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "")
public class AppConfig {

    private DbConfig sqlserver = new DbConfig();
    private DbConfig tidb = new DbConfig();
    private ConvertConfig convert = new ConvertConfig();

    public DbConfig getSqlserver() { return sqlserver; }
    public void setSqlserver(DbConfig sqlserver) { this.sqlserver = sqlserver; }
    public DbConfig getTidb() { return tidb; }
    public void setTidb(DbConfig tidb) { this.tidb = tidb; }
    public ConvertConfig getConvert() { return convert; }
    public void setConvert(ConvertConfig convert) { this.convert = convert; }

    public static class DbConfig {
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String sqlServerJdbcUrl() {
            return String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true", host, port, database);
        }

        public String tidbJdbcUrl() {
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4", host, port, database);
        }
    }

    public static class ConvertConfig {
        private List<String> schemas;
        private List<String> tables;
        private boolean dropIfExists = false;
        private boolean continueOnError = true;

        public List<String> getSchemas() { return schemas; }
        public void setSchemas(List<String> schemas) { this.schemas = schemas; }
        public List<String> getTables() { return tables; }
        public void setTables(List<String> tables) { this.tables = tables; }
        public boolean isDropIfExists() { return dropIfExists; }
        public void setDropIfExists(boolean dropIfExists) { this.dropIfExists = dropIfExists; }
        public boolean isContinueOnError() { return continueOnError; }
        public void setContinueOnError(boolean continueOnError) { this.continueOnError = continueOnError; }
    }
}
```

- [ ] **Step 2: Create `ColumnSchema.java`**

```java
package com.tool.model;

public class ColumnSchema {
    private String name;
    private String sqlServerType;   // raw type from SQL Server, e.g. "nvarchar"
    private Integer maxLength;      // character max length, null if not applicable
    private Integer precision;      // numeric precision
    private Integer scale;          // numeric scale
    private boolean nullable;
    private String defaultValue;    // raw default expression from SQL Server
    private boolean identity;       // AUTO_INCREMENT equivalent

    public ColumnSchema() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSqlServerType() { return sqlServerType; }
    public void setSqlServerType(String sqlServerType) { this.sqlServerType = sqlServerType; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public Integer getPrecision() { return precision; }
    public void setPrecision(Integer precision) { this.precision = precision; }
    public Integer getScale() { return scale; }
    public void setScale(Integer scale) { this.scale = scale; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public boolean isIdentity() { return identity; }
    public void setIdentity(boolean identity) { this.identity = identity; }
}
```

- [ ] **Step 3: Create `IndexSchema.java`**

```java
package com.tool.model;

import java.util.List;

public class IndexSchema {
    private String name;
    private boolean unique;
    private boolean clustered;
    private boolean fulltext;
    private boolean columnstore;
    private List<String> columns;
    private List<String> includeColumns;  // INCLUDE clause columns
    private String filterDefinition;      // WHERE clause for filtered indexes

    public IndexSchema() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
    public boolean isClustered() { return clustered; }
    public void setClustered(boolean clustered) { this.clustered = clustered; }
    public boolean isFulltext() { return fulltext; }
    public void setFulltext(boolean fulltext) { this.fulltext = fulltext; }
    public boolean isColumnstore() { return columnstore; }
    public void setColumnstore(boolean columnstore) { this.columnstore = columnstore; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<String> getIncludeColumns() { return includeColumns; }
    public void setIncludeColumns(List<String> includeColumns) { this.includeColumns = includeColumns; }
    public String getFilterDefinition() { return filterDefinition; }
    public void setFilterDefinition(String filterDefinition) { this.filterDefinition = filterDefinition; }
}
```

- [ ] **Step 4: Create `TableSchema.java`**

```java
package com.tool.model;

import java.util.ArrayList;
import java.util.List;

public class TableSchema {
    private String schemaName;      // e.g. "dbo"
    private String tableName;       // e.g. "orders"
    private List<ColumnSchema> columns = new ArrayList<>();
    private List<String> primaryKeyColumns = new ArrayList<>();
    private List<String> checkConstraints = new ArrayList<>();  // raw CHECK expressions
    private List<String> uniqueConstraintColumns = new ArrayList<>();  // each entry = one UNIQUE constraint column list CSV
    private List<IndexSchema> indexes = new ArrayList<>();
    private boolean partitioned = false;
    private int foreignKeyCount = 0;

    public TableSchema() {}

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public List<ColumnSchema> getColumns() { return columns; }
    public void setColumns(List<ColumnSchema> columns) { this.columns = columns; }
    public List<String> getPrimaryKeyColumns() { return primaryKeyColumns; }
    public void setPrimaryKeyColumns(List<String> primaryKeyColumns) { this.primaryKeyColumns = primaryKeyColumns; }
    public List<String> getCheckConstraints() { return checkConstraints; }
    public void setCheckConstraints(List<String> checkConstraints) { this.checkConstraints = checkConstraints; }
    public List<String> getUniqueConstraintColumns() { return uniqueConstraintColumns; }
    public void setUniqueConstraintColumns(List<String> uniqueConstraintColumns) { this.uniqueConstraintColumns = uniqueConstraintColumns; }
    public List<IndexSchema> getIndexes() { return indexes; }
    public void setIndexes(List<IndexSchema> indexes) { this.indexes = indexes; }
    public boolean isPartitioned() { return partitioned; }
    public void setPartitioned(boolean partitioned) { this.partitioned = partitioned; }
    public int getForeignKeyCount() { return foreignKeyCount; }
    public void setForeignKeyCount(int foreignKeyCount) { this.foreignKeyCount = foreignKeyCount; }

    public String getFullName() { return schemaName + "." + tableName; }
}
```

- [ ] **Step 5: Create `ConversionResult.java`**

```java
package com.tool;

import java.util.ArrayList;
import java.util.List;

public class ConversionResult {
    public enum Status { OK, WARN, ERROR }

    private final String tableName;
    private Status status = Status.OK;
    private final List<String> warnings = new ArrayList<>();
    private String errorMessage;

    public ConversionResult(String tableName) { this.tableName = tableName; }

    public void addWarning(String message) {
        warnings.add(message);
        if (status == Status.OK) status = Status.WARN;
    }

    public void setError(String message) {
        this.errorMessage = message;
        this.status = Status.ERROR;
    }

    public String getTableName() { return tableName; }
    public Status getStatus() { return status; }
    public List<String> getWarnings() { return warnings; }
    public String getErrorMessage() { return errorMessage; }
}
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat: add config, model, and result classes"
```

---

## Task 3: Type Mapper (with tests)

**Files:**
- Create: `src/main/java/com/tool/converter/TypeMapper.java`
- Create: `src/test/java/com/tool/converter/TypeMapperTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/tool/converter/TypeMapperTest.java
package com.tool.converter;

import com.tool.model.ColumnSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeMapperTest {

    private TypeMapper mapper = new TypeMapper();

    private ColumnSchema col(String type) {
        ColumnSchema c = new ColumnSchema();
        c.setSqlServerType(type);
        return c;
    }

    private ColumnSchema col(String type, int maxLength) {
        ColumnSchema c = col(type);
        c.setMaxLength(maxLength);
        return c;
    }

    private ColumnSchema col(String type, int precision, int scale) {
        ColumnSchema c = col(type);
        c.setPrecision(precision);
        c.setScale(scale);
        return c;
    }

    @Test void int_mapsToInt() { assertEquals("INT", mapper.mapType(col("int")).tidbType()); }
    @Test void bigint_mapsToBigint() { assertEquals("BIGINT", mapper.mapType(col("bigint")).tidbType()); }
    @Test void smallint_mapsToSmallint() { assertEquals("SMALLINT", mapper.mapType(col("smallint")).tidbType()); }
    @Test void tinyint_mapsToTinyintUnsigned() { assertEquals("TINYINT UNSIGNED", mapper.mapType(col("tinyint")).tidbType()); }
    @Test void bit_mapsToTinyint1() { assertEquals("TINYINT(1)", mapper.mapType(col("bit")).tidbType()); }
    @Test void decimal_mapsWithPrecision() { assertEquals("DECIMAL(10,2)", mapper.mapType(col("decimal", 10, 2)).tidbType()); }
    @Test void money_mapsToDecimal() { assertEquals("DECIMAL(19,4)", mapper.mapType(col("money")).tidbType()); assertTrue(mapper.mapType(col("money")).hasWarning()); }
    @Test void float_mapsToDouble() { assertEquals("DOUBLE", mapper.mapType(col("float")).tidbType()); }
    @Test void real_mapsToFloat() { assertEquals("FLOAT", mapper.mapType(col("real")).tidbType()); }
    @Test void date_mapsToDate() { assertEquals("DATE", mapper.mapType(col("date")).tidbType()); }
    @Test void datetime_mapsToDatetime() { assertEquals("DATETIME", mapper.mapType(col("datetime")).tidbType()); }
    @Test void datetime2_mapsWithWarning() { assertTrue(mapper.mapType(col("datetime2")).hasWarning()); }
    @Test void datetimeoffset_mapsWithWarning() { assertTrue(mapper.mapType(col("datetimeoffset")).hasWarning()); }
    @Test void varchar_mapsWithLength() { assertEquals("VARCHAR(100)", mapper.mapType(col("varchar", 100)).tidbType()); }
    @Test void varcharMax_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("varchar", -1)).tidbType()); assertTrue(mapper.mapType(col("varchar", -1)).hasWarning()); }
    @Test void nvarchar_mapsWithCharset() { assertEquals("VARCHAR(50) CHARACTER SET utf8mb4", mapper.mapType(col("nvarchar", 50)).tidbType()); }
    @Test void nvarcharMax_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("nvarchar", -1)).tidbType()); }
    @Test void text_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("text")).tidbType()); assertTrue(mapper.mapType(col("text")).hasWarning()); }
    @Test void image_mapsToLongblob() { assertEquals("LONGBLOB", mapper.mapType(col("image")).tidbType()); assertTrue(mapper.mapType(col("image")).hasWarning()); }
    @Test void uniqueidentifier_mapsToVarchar36() { assertEquals("VARCHAR(36)", mapper.mapType(col("uniqueidentifier")).tidbType()); }
    @Test void xml_mapsToLongtext() { assertEquals("LONGTEXT", mapper.mapType(col("xml")).tidbType()); }
    @Test void json_mapsToJson() { assertEquals("JSON", mapper.mapType(col("json")).tidbType()); }
    @Test void geometry_mapsToGeometry() { assertEquals("GEOMETRY", mapper.mapType(col("geometry")).tidbType()); }
    @Test void unknown_mapsToSkip() { assertTrue(mapper.mapType(col("someunknowntype")).skip()); }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=TypeMapperTest -q 2>&1 | tail -5
```
Expected: compilation error — `TypeMapper` does not exist yet.

- [ ] **Step 3: Create `TypeMapper.java`**

```java
package com.tool.converter;

import com.tool.model.ColumnSchema;

public class TypeMapper {

    public record MappedType(String tidbType, boolean hasWarning, boolean skip, String warningMessage) {
        public static MappedType of(String type) { return new MappedType(type, false, false, null); }
        public static MappedType warn(String type, String msg) { return new MappedType(type, true, false, msg); }
        public static MappedType skip(String msg) { return new MappedType(null, true, true, msg); }
    }

    public MappedType mapType(ColumnSchema col) {
        String type = col.getSqlServerType().toLowerCase().trim();
        Integer len = col.getMaxLength();
        Integer prec = col.getPrecision();
        Integer scale = col.getScale();

        return switch (type) {
            case "int", "integer" -> MappedType.of("INT");
            case "bigint" -> MappedType.of("BIGINT");
            case "smallint" -> MappedType.of("SMALLINT");
            case "tinyint" -> MappedType.of("TINYINT UNSIGNED");
            case "bit" -> MappedType.of("TINYINT(1)");
            case "decimal", "numeric" -> MappedType.of("DECIMAL(" + prec + "," + scale + ")");
            case "money" -> MappedType.warn("DECIMAL(19,4)", "MONEY converted to DECIMAL(19,4)");
            case "smallmoney" -> MappedType.warn("DECIMAL(10,4)", "SMALLMONEY converted to DECIMAL(10,4)");
            case "float" -> MappedType.of("DOUBLE");
            case "real" -> MappedType.of("FLOAT");
            case "date" -> MappedType.of("DATE");
            case "time" -> MappedType.of("TIME");
            case "datetime", "smalldatetime" -> MappedType.of("DATETIME");
            case "datetime2" -> MappedType.warn("DATETIME", "DATETIME2 converted to DATETIME, precision may be lost");
            case "datetimeoffset" -> MappedType.warn("DATETIME", "DATETIMEOFFSET converted to DATETIME, timezone info lost");
            case "char" -> MappedType.of("CHAR(" + len + ")");
            case "varchar" -> mapVarchar(len, false);
            case "text" -> MappedType.warn("LONGTEXT", "TEXT converted to LONGTEXT");
            case "nchar" -> MappedType.of("CHAR(" + len + ") CHARACTER SET utf8mb4");
            case "nvarchar" -> mapVarchar(len, true);
            case "ntext" -> MappedType.warn("LONGTEXT", "NTEXT converted to LONGTEXT");
            case "binary" -> MappedType.of("BINARY(" + len + ")");
            case "varbinary" -> len != null && len == -1
                    ? MappedType.warn("LONGBLOB", "VARBINARY(MAX) converted to LONGBLOB")
                    : MappedType.of("VARBINARY(" + len + ")");
            case "image" -> MappedType.warn("LONGBLOB", "IMAGE converted to LONGBLOB (deprecated type)");
            case "uniqueidentifier" -> MappedType.warn("VARCHAR(36)", "UNIQUEIDENTIFIER converted to VARCHAR(36)");
            case "rowversion", "timestamp" -> MappedType.warn("BIGINT UNSIGNED", "ROWVERSION/TIMESTAMP (row version) converted to BIGINT UNSIGNED");
            case "xml" -> MappedType.warn("LONGTEXT", "XML converted to LONGTEXT");
            case "json" -> MappedType.of("JSON");
            case "vector" -> MappedType.warn("VECTOR", "VECTOR requires TiDB v8.4+");
            case "hierarchyid" -> MappedType.warn("VARCHAR(4000)", "HIERARCHYID converted to VARCHAR(4000)");
            case "geography" -> MappedType.warn("GEOMETRY", "GEOGRAPHY converted to GEOMETRY");
            case "geometry" -> MappedType.of("GEOMETRY");
            case "sql_variant" -> MappedType.warn("LONGTEXT", "SQL_VARIANT converted to LONGTEXT");
            case "sysname" -> MappedType.of("VARCHAR(128)");
            case "cursor", "table" -> MappedType.skip(type.toUpperCase() + " is not applicable to table columns");
            default -> MappedType.skip("Unknown type '" + type + "' — column skipped");
        };
    }

    private MappedType mapVarchar(Integer len, boolean unicode) {
        String charset = unicode ? " CHARACTER SET utf8mb4" : "";
        if (len == null || len == -1) {
            return MappedType.warn("LONGTEXT", (unicode ? "NVARCHAR" : "VARCHAR") + "(MAX) converted to LONGTEXT");
        }
        // nvarchar stores 2 bytes per char; maxLength from sys.columns is in bytes
        int charLen = unicode ? len / 2 : len;
        return MappedType.of("VARCHAR(" + charLen + ")" + charset);
    }

    public String mapDefaultValue(String rawDefault) {
        if (rawDefault == null) return null;
        // Strip SQL Server wrapping parens: ((value)) or (value)
        String val = rawDefault.trim();
        while (val.startsWith("(") && val.endsWith(")")) {
            val = val.substring(1, val.length() - 1).trim();
        }
        return switch (val.toUpperCase()) {
            case "GETDATE()" -> "CURRENT_TIMESTAMP";
            case "GETUTCDATE()" -> "UTC_TIMESTAMP()";
            case "NEWID()" -> "UUID()";
            case "NEWSEQUENTIALID()" -> "UUID()";
            case "SYSDATETIME()" -> "CURRENT_TIMESTAMP(6)";
            default -> val;
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=TypeMapperTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add TypeMapper with full SQL Server to TiDB type mapping"
```

---

## Task 4: SQL Server Extractor

**Files:**
- Create: `src/main/java/com/tool/extractor/SqlServerExtractor.java`

- [ ] **Step 1: Create `SqlServerExtractor.java`**

```java
package com.tool.extractor;

import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class SqlServerExtractor {

    /**
     * List all tables in the given schemas (or all schemas if schemasFilter is empty/null).
     * Returns list of [schemaName, tableName] pairs.
     */
    public List<String[]> listTables(Connection conn, List<String> schemasFilter, List<String> tablesFilter) throws SQLException {
        List<String[]> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT s.name AS schema_name, t.name AS table_name " +
            "FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id " +
            "WHERE t.type = 'U'"
        );
        if (schemasFilter != null && !schemasFilter.isEmpty()) {
            sql.append(" AND s.name IN (").append(placeholders(schemasFilter.size())).append(")");
        }
        if (tablesFilter != null && !tablesFilter.isEmpty()) {
            sql.append(" AND t.name IN (").append(placeholders(tablesFilter.size())).append(")");
        }
        sql.append(" ORDER BY s.name, t.name");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (schemasFilter != null) for (String s : schemasFilter) ps.setString(idx++, s);
            if (tablesFilter != null) for (String t : tablesFilter) ps.setString(idx++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{rs.getString("schema_name"), rs.getString("table_name")});
            }
        }
        return result;
    }

    public TableSchema extractTable(Connection conn, String schemaName, String tableName) throws SQLException {
        TableSchema table = new TableSchema();
        table.setSchemaName(schemaName);
        table.setTableName(tableName);
        table.setColumns(extractColumns(conn, schemaName, tableName));
        table.setPrimaryKeyColumns(extractPrimaryKey(conn, schemaName, tableName));
        table.setCheckConstraints(extractCheckConstraints(conn, schemaName, tableName));
        table.setUniqueConstraintColumns(extractUniqueConstraints(conn, schemaName, tableName));
        table.setIndexes(extractIndexes(conn, schemaName, tableName));
        table.setForeignKeyCount(countForeignKeys(conn, schemaName, tableName));
        table.setPartitioned(isPartitioned(conn, schemaName, tableName));
        return table;
    }

    private List<ColumnSchema> extractColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT c.name, tp.name AS type_name,
                   c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity,
                   dc.definition AS default_value
            FROM sys.columns c
            JOIN sys.types tp ON c.user_type_id = tp.user_type_id
            JOIN sys.tables t ON c.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
            WHERE s.name = ? AND t.name = ?
            ORDER BY c.column_id
            """;
        List<ColumnSchema> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnSchema col = new ColumnSchema();
                    col.setName(rs.getString("name"));
                    col.setSqlServerType(rs.getString("type_name"));
                    col.setMaxLength(rs.getInt("max_length") == 0 ? null : rs.getInt("max_length"));
                    col.setPrecision(rs.getInt("precision") == 0 ? null : rs.getInt("precision"));
                    col.setScale(rs.getInt("scale") == 0 ? null : rs.getInt("scale"));
                    col.setNullable(rs.getBoolean("is_nullable"));
                    col.setIdentity(rs.getBoolean("is_identity"));
                    col.setDefaultValue(rs.getString("default_value"));
                    cols.add(col);
                }
            }
        }
        return cols;
    }

    private List<String> extractPrimaryKey(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT c.name
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE i.is_primary_key = 1 AND s.name = ? AND t.name = ?
            ORDER BY ic.key_ordinal
            """;
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private List<String> extractCheckConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT cc.definition
            FROM sys.check_constraints cc
            JOIN sys.tables t ON cc.parent_object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
            """;
        List<String> checks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) checks.add(rs.getString("definition"));
            }
        }
        return checks;
    }

    private List<String> extractUniqueConstraints(Connection conn, String schema, String table) throws SQLException {
        // Returns one entry per unique constraint; each entry is CSV of column names
        String sql = """
            SELECT i.name AS idx_name,
                   STRING_AGG(c.name, ',') WITHIN GROUP (ORDER BY ic.key_ordinal) AS cols
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE i.is_unique_constraint = 1 AND s.name = ? AND t.name = ?
            GROUP BY i.name
            """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("cols"));
            }
        }
        return result;
    }

    private List<IndexSchema> extractIndexes(Connection conn, String schema, String table) throws SQLException {
        // Get index metadata (excluding PKs and unique constraints — handled separately)
        String idxSql = """
            SELECT i.name, i.is_unique, i.type_desc,
                   i.filter_definition,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM sys.fulltext_indexes fi WHERE fi.object_id = i.object_id
                   ) THEN 1 ELSE 0 END AS is_fulltext
            FROM sys.indexes i
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
              AND i.is_primary_key = 0 AND i.is_unique_constraint = 0
              AND i.type > 0
            """;
        String colSql = """
            SELECT c.name, ic.is_included_column
            FROM sys.index_columns ic
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ? AND i.name = ?
            ORDER BY ic.is_included_column, ic.key_ordinal
            """;
        List<IndexSchema> indexes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(idxSql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexSchema idx = new IndexSchema();
                    idx.setName(rs.getString("name"));
                    idx.setUnique(rs.getBoolean("is_unique"));
                    String typeDesc = rs.getString("type_desc");
                    idx.setClustered("CLUSTERED".equalsIgnoreCase(typeDesc));
                    idx.setColumnstore(typeDesc != null && typeDesc.toUpperCase().contains("COLUMNSTORE"));
                    idx.setFulltext(rs.getInt("is_fulltext") == 1);
                    idx.setFilterDefinition(rs.getString("filter_definition"));

                    List<String> keyCols = new ArrayList<>(), includeCols = new ArrayList<>();
                    try (PreparedStatement ps2 = conn.prepareStatement(colSql)) {
                        ps2.setString(1, schema); ps2.setString(2, table); ps2.setString(3, idx.getName());
                        try (ResultSet rs2 = ps2.executeQuery()) {
                            while (rs2.next()) {
                                if (rs2.getBoolean("is_included_column")) includeCols.add(rs2.getString("name"));
                                else keyCols.add(rs2.getString("name"));
                            }
                        }
                    }
                    idx.setColumns(keyCols);
                    idx.setIncludeColumns(includeCols);
                    indexes.add(idx);
                }
            }
        }
        return indexes;
    }

    private int countForeignKeys(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM sys.foreign_keys fk
            JOIN sys.tables t ON fk.parent_object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean isPartitioned(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM sys.partitions p
            JOIN sys.tables t ON p.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ? AND p.partition_number > 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "feat: add SqlServerExtractor reading schema from sys.* tables"
```

---

## Task 5: Schema Converter (with tests)

**Files:**
- Create: `src/main/java/com/tool/converter/SchemaConverter.java`
- Create: `src/test/java/com/tool/converter/SchemaConverterTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/tool/converter/SchemaConverterTest.java
package com.tool.converter;

import com.tool.ConversionResult;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaConverterTest {

    private SchemaConverter converter;

    @BeforeEach
    void setUp() { converter = new SchemaConverter(new TypeMapper()); }

    private TableSchema simpleTable() {
        TableSchema t = new TableSchema();
        t.setSchemaName("dbo");
        t.setTableName("users");
        ColumnSchema id = new ColumnSchema();
        id.setName("id"); id.setSqlServerType("int"); id.setNullable(false); id.setIdentity(true);
        ColumnSchema name = new ColumnSchema();
        name.setName("name"); name.setSqlServerType("nvarchar"); name.setMaxLength(100); name.setNullable(false);
        t.setColumns(List.of(id, name));
        t.setPrimaryKeyColumns(List.of("id"));
        return t;
    }

    @Test
    void generatesCreateTable() {
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(simpleTable(), result, false);
        assertTrue(ddl.contains("CREATE TABLE `users`"));
        assertTrue(ddl.contains("`id` INT NOT NULL AUTO_INCREMENT"));
        assertTrue(ddl.contains("`name` VARCHAR(50) CHARACTER SET utf8mb4 NOT NULL"));
        assertTrue(ddl.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    void dropIfExists_prependsDropStatement() {
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(simpleTable(), result, true);
        assertTrue(ddl.startsWith("DROP TABLE IF EXISTS `users`;"));
    }

    @Test
    void partitionedTable_addsWarning() {
        TableSchema t = simpleTable();
        t.setPartitioned(true);
        ConversionResult result = new ConversionResult("dbo.users");
        converter.toCreateTableDDL(t, result, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("partitioned table")));
    }

    @Test
    void foreignKey_addsWarningAndDrops() {
        TableSchema t = simpleTable();
        t.setForeignKeyCount(2);
        ConversionResult result = new ConversionResult("dbo.users");
        converter.toCreateTableDDL(t, result, false);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("foreign key")));
    }

    @Test
    void checkConstraint_includesInDDL() {
        TableSchema t = simpleTable();
        t.setCheckConstraints(List.of("([age]>(0))"));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("CHECK"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("CHECK")));
    }

    @Test
    void fulltextIndex_isDiscarded() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("ft_idx"); idx.setFulltext(true); idx.setColumns(List.of("name"));
        t.setIndexes(List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertFalse(ddl.contains("ft_idx"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("FULLTEXT")));
    }

    @Test
    void normalIndex_isIncluded() {
        TableSchema t = simpleTable();
        IndexSchema idx = new IndexSchema();
        idx.setName("idx_name"); idx.setUnique(false); idx.setClustered(false);
        idx.setColumns(List.of("name")); idx.setIncludeColumns(List.of());
        t.setIndexes(List.of(idx));
        ConversionResult result = new ConversionResult("dbo.users");
        String ddl = converter.toCreateTableDDL(t, result, false);
        assertTrue(ddl.contains("INDEX `idx_name`"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=SchemaConverterTest -q 2>&1 | tail -5
```
Expected: compilation error — `SchemaConverter` does not exist yet.

- [ ] **Step 3: Create `SchemaConverter.java`**

```java
package com.tool.converter;

import com.tool.ConversionResult;
import com.tool.model.ColumnSchema;
import com.tool.model.IndexSchema;
import com.tool.model.TableSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SchemaConverter {

    private final TypeMapper typeMapper;

    public SchemaConverter(TypeMapper typeMapper) { this.typeMapper = typeMapper; }

    /**
     * Produces the full DDL for a table (CREATE TABLE + CREATE INDEX statements).
     */
    public String toCreateTableDDL(TableSchema table, ConversionResult result, boolean dropIfExists) {
        StringBuilder sb = new StringBuilder();

        if (table.isPartitioned()) {
            result.addWarning("partitioned table detected, converted to regular table");
        }
        if (table.getForeignKeyCount() > 0) {
            result.addWarning(table.getForeignKeyCount() + " foreign key(s) discarded");
        }

        if (dropIfExists) {
            sb.append("DROP TABLE IF EXISTS `").append(table.getTableName()).append("`;\n\n");
        }

        sb.append("CREATE TABLE `").append(table.getTableName()).append("` (\n");

        List<String> parts = new ArrayList<>();

        // Columns
        for (ColumnSchema col : table.getColumns()) {
            TypeMapper.MappedType mapped = typeMapper.mapType(col);
            if (mapped.skip()) {
                result.addWarning("column '" + col.getName() + "': " + mapped.warningMessage() + " — column skipped");
                continue;
            }
            if (mapped.hasWarning()) {
                result.addWarning("column '" + col.getName() + "': " + mapped.warningMessage());
            }

            StringBuilder colDef = new StringBuilder();
            colDef.append("  `").append(col.getName()).append("` ").append(mapped.tidbType());
            if (!col.isNullable()) colDef.append(" NOT NULL");
            if (col.isIdentity()) colDef.append(" AUTO_INCREMENT");

            String defaultVal = typeMapper.mapDefaultValue(col.getDefaultValue());
            if (defaultVal != null && !col.isIdentity()) {
                colDef.append(" DEFAULT ").append(defaultVal);
            }
            parts.add(colDef.toString());
        }

        // Primary key
        if (!table.getPrimaryKeyColumns().isEmpty()) {
            parts.add("  PRIMARY KEY (" + quoteCols(table.getPrimaryKeyColumns()) + ")");
        }

        // Unique constraints
        for (String uniqueCols : table.getUniqueConstraintColumns()) {
            List<String> cols = List.of(uniqueCols.split(","));
            parts.add("  UNIQUE KEY (" + quoteCols(cols) + ")");
        }

        // Check constraints
        for (String check : table.getCheckConstraints()) {
            // Strip outer parens added by SQL Server
            String expr = check.trim();
            if (expr.startsWith("(") && expr.endsWith(")")) expr = expr.substring(1, expr.length() - 1);
            parts.add("  CHECK (" + expr + ")");
            result.addWarning("CHECK constraint included — requires TiDB 8.0+");
        }

        sb.append(String.join(",\n", parts));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        // Indexes (appended after CREATE TABLE)
        for (IndexSchema idx : table.getIndexes()) {
            String indexDdl = buildIndexDDL(table.getTableName(), idx, result);
            if (indexDdl != null) sb.append("\n").append(indexDdl).append("\n");
        }

        return sb.toString();
    }

    private String buildIndexDDL(String tableName, IndexSchema idx, ConversionResult result) {
        if (idx.isFulltext()) {
            result.addWarning("FULLTEXT index '" + idx.getName() + "' discarded — not supported in TiDB");
            return null;
        }
        if (idx.isColumnstore()) {
            result.addWarning("COLUMNSTORE index '" + idx.getName() + "' discarded — not supported in TiDB");
            return null;
        }
        if (idx.getColumns() == null || idx.getColumns().isEmpty()) return null;

        if (idx.isClustered()) {
            result.addWarning("CLUSTERED index '" + idx.getName() + "' converted to regular INDEX");
        }
        if (idx.getIncludeColumns() != null && !idx.getIncludeColumns().isEmpty()) {
            result.addWarning("index '" + idx.getName() + "': INCLUDE columns dropped — not supported in TiDB");
        }
        if (idx.getFilterDefinition() != null) {
            result.addWarning("index '" + idx.getName() + "': WHERE filter dropped — filtered indexes not supported in TiDB");
        }

        String uniqueKeyword = idx.isUnique() ? "UNIQUE " : "";
        return "CREATE " + uniqueKeyword + "INDEX `" + idx.getName() + "` ON `" + tableName + "` ("
                + quoteCols(idx.getColumns()) + ");";
    }

    private String quoteCols(List<String> cols) {
        return cols.stream().map(c -> "`" + c.trim() + "`").collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=SchemaConverterTest,TypeMapperTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add SchemaConverter building TiDB DDL from TableSchema"
```

---

## Task 6: TiDB Writer

**Files:**
- Create: `src/main/java/com/tool/writer/TiDBWriter.java`

- [ ] **Step 1: Create `TiDBWriter.java`**

```java
package com.tool.writer;

import com.tool.ConversionResult;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class TiDBWriter {

    /**
     * Executes the full DDL string (may contain multiple statements separated by semicolons).
     * Each statement is executed individually.
     */
    public void executeDDL(Connection conn, String ddl, ConversionResult result) {
        String[] statements = ddl.split(";");
        try (Statement stmt = conn.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) continue;
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            result.setError("failed to execute DDL: " + e.getMessage());
        }
    }

    /**
     * Prints DDL to stdout without executing (dry-run mode).
     */
    public void printDDL(String tableName, String ddl) {
        System.out.println("-- DDL for " + tableName);
        System.out.println(ddl);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "feat: add TiDBWriter executing DDL on TiDB"
```

---

## Task 7: App Entry Point and Orchestration

**Files:**
- Create: `src/main/java/com/tool/App.java`

- [ ] **Step 1: Create `App.java`**

```java
package com.tool;

import com.tool.config.AppConfig;
import com.tool.converter.SchemaConverter;
import com.tool.converter.TypeMapper;
import com.tool.extractor.SqlServerExtractor;
import com.tool.model.TableSchema;
import com.tool.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.sql.Connection;
import java.sql.DriverManager;
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

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter, TiDBWriter writer) {
        this.config = config;
        this.extractor = extractor;
        this.converter = new SchemaConverter(new TypeMapper());
        this.writer = writer;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = args.containsOption("dry-run");

        // Override tables from CLI if provided
        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(",")).map(String::trim).collect(Collectors.toList());
        }

        List<String> schemas = config.getConvert().getSchemas();
        List<String> tables = tablesOverride != null ? tablesOverride : config.getConvert().getTables();
        boolean dropIfExists = config.getConvert().isDropIfExists();
        boolean continueOnError = config.getConvert().isContinueOnError();

        log("INFO", "Connecting to SQL Server...");
        try (Connection ssConn = DriverManager.getConnection(
                config.getSqlserver().sqlServerJdbcUrl(),
                config.getSqlserver().getUsername(),
                config.getSqlserver().getPassword())) {

            Connection tidbConn = null;
            if (!dryRun) {
                log("INFO", "Connecting to TiDB...");
                tidbConn = DriverManager.getConnection(
                        config.getTidb().tidbJdbcUrl(),
                        config.getTidb().getUsername(),
                        config.getTidb().getPassword());
            }

            List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
            log("INFO", "Starting conversion, found " + tableList.size() + " tables");

            int succeeded = 0, warned = 0, failed = 0;

            for (int i = 0; i < tableList.size(); i++) {
                String[] entry = tableList.get(i);
                String schemaName = entry[0], tableName = entry[1];
                String fullName = schemaName + "." + tableName;
                String progress = "[" + (i + 1) + "/" + tableList.size() + "]";

                ConversionResult result = new ConversionResult(fullName);
                try {
                    TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                    String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);

                    if (dryRun) {
                        writer.printDDL(fullName, ddl);
                    } else {
                        writer.executeDDL(tidbConn, ddl, result);
                    }
                } catch (Exception e) {
                    result.setError(e.getMessage());
                }

                switch (result.getStatus()) {
                    case OK -> { log("INFO", progress + " Converting table " + fullName + " ... OK"); succeeded++; }
                    case WARN -> {
                        for (String w : result.getWarnings()) log("WARN", progress + " Converting table " + fullName + " ... " + w);
                        warned++;
                    }
                    case ERROR -> {
                        log("ERROR", progress + " Converting table " + fullName + " ... " + result.getErrorMessage());
                        failed++;
                        if (!continueOnError) break;
                    }
                }
            }

            if (tidbConn != null) tidbConn.close();

            log("INFO", "Conversion completed: " + succeeded + " succeeded, " + warned + " warnings, " + failed + " failed");
            if (failed > 0) System.exit(1);
        }
    }

    private void log(String level, String message) {
        System.out.printf("[%-5s] %s%n", level, message);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Run all tests**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 4: Build fat JAR**

```bash
mvn package -DskipTests -q
ls -lh target/sqlserver-to-tidb-1.0.0.jar
```
Expected: JAR file present, ~30-50 MB.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add App entry point with CLI orchestration and dry-run support"
```

---

## Task 8: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
mvn test
```
Expected: All tests pass, `BUILD SUCCESS`.

- [ ] **Step 2: Verify JAR runs with --help equivalent (no args)**

```bash
java -jar target/sqlserver-to-tidb-1.0.0.jar --dry-run 2>&1 | head -5
```
Expected: Prints `[INFO ] Connecting to SQL Server...` then a connection error (no real DB available — that's fine, proves the JAR starts correctly).

- [ ] **Step 3: Final commit**

```bash
git add .
git commit -m "chore: final build verification"
```
