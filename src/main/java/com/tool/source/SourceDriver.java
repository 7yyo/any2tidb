package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.schema.converter.SqlServerTypeMapper;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;

import java.util.Set;

/**
 * Pluggable source database driver.
 * One implementation per supported source (sqlserver, postgresql, db2, ...).
 */
public interface SourceDriver {

    /** Short identifier for the source, e.g. "sqlserver". */
    String type();

    /** CLI flags unique to this source (beyond common + mode flags). */
    Set<String> ownFlags();

    SchemaExtractor schemaExtractor();
    DumpExtractor dumpExtractor();
    SqlServerTypeMapper typeMapper();
    SchemaVerifier verifier();
    ConsistencyProvider consistencyProvider();
    CdcProvider cdcProvider();

    /** Build a JDBC URL to the source server (no specific database). */
    String buildJdbcUrl(AppConfig.DbConfig db);

    /** Build a JDBC URL targeting a specific database on the source. */
    String buildJdbcUrlTo(AppConfig.DbConfig db, String database);

    /** Fully qualified Debezium connector class name for this source. */
    String debeziumConnectorClass();
}
