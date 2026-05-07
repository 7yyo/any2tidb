package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.schema.converter.MySqlTypeMapper;
import com.tool.schema.converter.TypeMapper;
import com.tool.schema.extractor.MySqlExtractor;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.MySqlSchemaVerifier;
import com.tool.schema.verifier.SchemaVerifier;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.Set;

@Component
public class MySqlDriver implements SourceDriver {

    private final MySqlExtractor schemaExtractor;
    private final MySqlTypeMapper typeMapper;
    private final MySqlSchemaVerifier verifier;
    private final MySqlCdcProvider cdcProvider;

    public MySqlDriver(AppConfig config) {
        this.schemaExtractor = new MySqlExtractor();
        this.typeMapper = new MySqlTypeMapper();
        this.verifier = new MySqlSchemaVerifier();
        this.cdcProvider = new MySqlCdcProvider();
    }

    @Override
    public String type() { return "mysql"; }

    @Override
    public Set<String> supportedModes() { return Set.of("schema", "snapshot", "sync"); }

    @Override
    public Set<String> ownFlags() { return Set.of(); }

    @Override
    public SchemaExtractor schemaExtractor() { return schemaExtractor; }

    @Override
    public DumpExtractor dumpExtractor() {
        throw new UnsupportedOperationException("dump mode not yet supported for MySQL");
    }

    @Override
    public TypeMapper typeMapper() { return typeMapper; }

    @Override
    public SchemaVerifier verifier() { return verifier; }

    @Override
    public ConsistencyProvider consistencyProvider() {
        throw new UnsupportedOperationException("not needed for MySQL");
    }

    @Override
    public CdcProvider cdcProvider() { return cdcProvider; }

    @Override
    public String buildJdbcUrl(AppConfig.DbConfig db) {
        return String.format("jdbc:mysql://%s:%d?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000",
                db.getHost(), db.getPort());
    }

    @Override
    public String buildJdbcUrlTo(AppConfig.DbConfig db, String database) {
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000",
                db.getHost(), db.getPort(), database);
    }

    @Override
    public String debeziumConnectorClass() {
        return "io.debezium.connector.mysql.MySqlConnector";
    }

    @Override
    public void configureDebeziumProperties(Properties props) {
        props.setProperty("snapshot.locking.mode", "minimal");
        props.setProperty("database.server.id", "1");
    }

    @Override
    public String debeziumDatabaseProperty() { return "database.include.list"; }
}
