package com.tool.source;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.schema.converter.SqlServerTypeMapper;
import com.tool.schema.extractor.SchemaExtractor;
import com.tool.schema.verifier.SchemaVerifier;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SqlServerDriver implements SourceDriver {

    private final SchemaExtractor schemaExtractor;
    private final DumpExtractor dumpExtractor;
    private final SqlServerTypeMapper typeMapper;
    private final SchemaVerifier verifier;
    private final ConsistencyProvider consistencyProvider;

    public SqlServerDriver(SchemaExtractor schemaExtractor,
                           DumpExtractor dumpExtractor,
                           SqlServerTypeMapper typeMapper,
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
    public SqlServerTypeMapper typeMapper() { return typeMapper; }

    @Override
    public SchemaVerifier verifier() { return verifier; }

    @Override
    public ConsistencyProvider consistencyProvider() { return consistencyProvider; }
}
