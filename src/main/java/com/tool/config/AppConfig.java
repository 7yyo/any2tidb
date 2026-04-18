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
