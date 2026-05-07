package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.schema.converter.TypeMapper;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;

import java.util.Properties;
import java.util.Set;

/**
 * Pluggable source database driver.
 * One implementation per supported source (sqlserver, postgresql, db2, ...).
 */
public interface SourceDriver {

    /** Short identifier for the source, e.g. "sqlserver". */
    String type();

    /** Modes this source supports. Default: all four. */
    default Set<String> supportedModes() {
        return Set.of("schema", "dump", "snapshot", "sync");
    }

    /** CLI flags unique to this source (beyond common + mode flags). */
    Set<String> ownFlags();

    SchemaExtractor schemaExtractor();
    DumpExtractor dumpExtractor();
    TypeMapper typeMapper();
    SchemaVerifier verifier();
    ConsistencyProvider consistencyProvider();
    CdcProvider cdcProvider();

    /** Build a JDBC URL to the source server (no specific database). */
    String buildJdbcUrl(AppConfig.DbConfig db);

    /** Build a JDBC URL targeting a specific database on the source. */
    String buildJdbcUrlTo(AppConfig.DbConfig db, String database);

    /** Fully qualified Debezium connector class name for this source. */
    String debeziumConnectorClass();

    /** Set source-specific Debezium engine properties (SSL, locking mode, etc.). */
    default void configureDebeziumProperties(Properties props) {}

    /** Debezium property name for specifying the database(s) to capture. */
    default String debeziumDatabaseProperty() { return "database.names"; }

    /**
     * Capture the current CDC start point (e.g. LSN) for the given database,
     * enabling incremental sync to resume from this point. Returns null if CDC
     * is not available or the source does not support it.
     */
    default String captureCdcStartPoint(String dbName) { return null; }
}
