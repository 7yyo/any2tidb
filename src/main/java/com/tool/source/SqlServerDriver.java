package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.schema.converter.TypeMapper;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SqlServerDriver implements SourceDriver {

    private final SchemaExtractor schemaExtractor;
    private final DumpExtractor dumpExtractor;
    private final TypeMapper typeMapper;
    private final SchemaVerifier verifier;
    private final ConsistencyProvider consistencyProvider;
    private final CdcProvider cdcProvider;

    public SqlServerDriver(SchemaExtractor schemaExtractor,
                           DumpExtractor dumpExtractor,
                           TypeMapper typeMapper,
                           SchemaVerifier verifier,
                           AppConfig config) {
        this.schemaExtractor = schemaExtractor;
        this.dumpExtractor = dumpExtractor;
        this.typeMapper = typeMapper;
        this.verifier = verifier;
        this.consistencyProvider = new SqlServerConsistencyProvider(
                config.getSource().getHost(),
                config.getSource().getPort(),
                config.getSource().getUsername(),
                config.getSource().getPassword());
        this.cdcProvider = new SqlServerCdcProvider(config.getSource());
    }

    @Override
    public String type() { return "sqlserver"; }

    @Override
    public Set<String> ownFlags() {
        return Set.of("enable-cdc");
    }

    @Override
    public SchemaExtractor schemaExtractor() { return schemaExtractor; }

    @Override
    public DumpExtractor dumpExtractor() { return dumpExtractor; }

    @Override
    public TypeMapper typeMapper() { return typeMapper; }

    @Override
    public SchemaVerifier verifier() { return verifier; }

    @Override
    public ConsistencyProvider consistencyProvider() { return consistencyProvider; }

    @Override
    public CdcProvider cdcProvider() { return cdcProvider; }

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

    @Override
    public String debeziumConnectorClass() {
        return "io.debezium.connector.sqlserver.SqlServerConnector";
    }
}
