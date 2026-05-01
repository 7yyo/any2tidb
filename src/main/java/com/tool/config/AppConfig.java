package com.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "")
public class AppConfig {

    private DbConfig source = new DbConfig();
    private DbConfig target = new DbConfig();

    public DbConfig getSource() { return source; }
    public void setSource(DbConfig source) { this.source = source; }
    public DbConfig getTarget() { return target; }
    public void setTarget(DbConfig target) { this.target = target; }

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

        public String tidbJdbcUrl() {
            return String.format("jdbc:mysql://%s:%d?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&characterEncoding=utf8&connectTimeout=30000&socketTimeout=300000", host, port);
        }
    }
}
