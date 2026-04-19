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

    public DbConfig getSource() { return source; }
    public void setSource(DbConfig source) { this.source = source; }
    public DbConfig getTarget() { return target; }
    public void setTarget(DbConfig target) { this.target = target; }
    public ConvertConfig getConvert() { return convert; }
    public void setConvert(ConvertConfig convert) { this.convert = convert; }

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
}
