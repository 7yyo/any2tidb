package com.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "")
public class AppConfig {

    private DbConfig source = new DbConfig();
    private DbConfig target = new DbConfig();
    private ConvertConfig convert = new ConvertConfig();
    private DumpConfig dump = new DumpConfig();

    public DbConfig getSource() { return source; }
    public void setSource(DbConfig source) { this.source = source; }
    public DbConfig getTarget() { return target; }
    public void setTarget(DbConfig target) { this.target = target; }
    public ConvertConfig getConvert() { return convert; }
    public void setConvert(ConvertConfig convert) { this.convert = convert; }
    public DumpConfig getDump() { return dump; }
    public void setDump(DumpConfig dump) { this.dump = dump; }

    public static class DbConfig {
        private String host;
        private int port;
        private String username;
        private String password;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String sqlServerJdbcUrlNoDB() {
            return String.format("jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true;loginTimeout=30;socketTimeout=300", host, port);
        }

        public String sqlServerJdbcUrlForDB(String dbName) {
            return String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=30;socketTimeout=300", host, port, dbName);
        }

        public String tidbJdbcUrlNoDB() {
            return String.format("jdbc:mysql://%s:%d?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=30000&socketTimeout=300000", host, port);
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

    public static class DumpConfig {
        /** Root output directory. Empty = current working directory. */
        private String outputDir = "";

        /** Maximum CSV file size in MB before rolling to next chunk. 0 = no limit. */
        private int fileSizeMb = 256;

        /** JDBC server-side cursor fetch size (rows per network round-trip). */
        private int chunkSize = 10000;

        /** Generate companion CREATE TABLE .sql files alongside CSV output. */
        private boolean generateSchema = true;

        /** Append WITH (NOLOCK) to SELECT — reduces lock contention, allows dirty reads. */
        private boolean nolock = true;

        /**
         * Enable SNAPSHOT isolation mode: auto-enables library-level snapshot isolation,
         * sets SNAPSHOT isolation level on each export connection, and records startLsn in
         * dump-meta.json. When true, {@code nolock} is ignored.
         */
        private boolean snapshotIsolation = false;

        /** Number of tables exported concurrently. */
        private int concurrency = 4;

        public String getOutputDir()          { return outputDir; }
        public void setOutputDir(String v)    { this.outputDir = v; }
        public int getFileSizeMb()            { return fileSizeMb; }
        public void setFileSizeMb(int v)      { this.fileSizeMb = v; }
        public int getChunkSize()             { return chunkSize; }
        public void setChunkSize(int v)       { this.chunkSize = v; }
        public boolean isGenerateSchema()     { return generateSchema; }
        public void setGenerateSchema(boolean v) { this.generateSchema = v; }
        public boolean isNolock()             { return nolock; }
        public void setNolock(boolean v)      { this.nolock = v; }
        public boolean isSnapshotIsolation()  { return snapshotIsolation; }
        public void setSnapshotIsolation(boolean v) { this.snapshotIsolation = v; }
        public int getConcurrency()           { return concurrency; }
        public void setConcurrency(int v)     { this.concurrency = v; }

        /** File size threshold in bytes. Returns {@link Long#MAX_VALUE} when fileSizeMb == 0. */
        public long fileSizeThresholdBytes() {
            return fileSizeMb <= 0 ? Long.MAX_VALUE : (long) fileSizeMb * 1024 * 1024;
        }
    }
}
