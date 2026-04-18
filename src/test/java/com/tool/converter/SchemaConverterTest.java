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
