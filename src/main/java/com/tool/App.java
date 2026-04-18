package com.tool;

import com.tool.config.AppConfig;
import com.tool.converter.SchemaConverter;
import com.tool.extractor.SqlServerExtractor;
import com.tool.model.TableSchema;
import com.tool.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootApplication
@EnableConfigurationProperties
public class App implements ApplicationRunner {

    private final AppConfig config;
    private final SqlServerExtractor extractor;
    private final SchemaConverter converter;
    private final TiDBWriter writer;

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter, TiDBWriter writer) {
        this.config = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer = writer;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = args.containsOption("dry-run");

        // Override tables from CLI if provided
        List<String> tablesOverride = null;
        if (args.containsOption("tables")) {
            String val = args.getOptionValues("tables").get(0);
            tablesOverride = Arrays.stream(val.split(",")).map(String::trim).collect(Collectors.toList());
        }

        List<String> schemas = config.getConvert().getSchemas();
        List<String> tables = tablesOverride != null ? tablesOverride : config.getConvert().getTables();
        boolean dropIfExists = config.getConvert().isDropIfExists();
        boolean continueOnError = config.getConvert().isContinueOnError();

        log("INFO", "Connecting to SQL Server...");
        try (Connection ssConn = DriverManager.getConnection(
                config.getSqlserver().sqlServerJdbcUrl(),
                config.getSqlserver().getUsername(),
                config.getSqlserver().getPassword())) {

            Connection tidbConn = null;
            if (!dryRun) {
                log("INFO", "Connecting to TiDB...");
                tidbConn = DriverManager.getConnection(
                        config.getTidb().tidbJdbcUrl(),
                        config.getTidb().getUsername(),
                        config.getTidb().getPassword());
            }

            List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
            log("INFO", "Starting conversion, found " + tableList.size() + " tables");

            int succeeded = 0, warned = 0, failed = 0;

            for (int i = 0; i < tableList.size(); i++) {
                String[] entry = tableList.get(i);
                String schemaName = entry[0], tableName = entry[1];
                String fullName = schemaName + "." + tableName;
                String progress = "[" + (i + 1) + "/" + tableList.size() + "]";

                ConversionResult result = new ConversionResult(fullName);
                try {
                    TableSchema tableSchema = extractor.extractTable(ssConn, schemaName, tableName);
                    String ddl = converter.toCreateTableDDL(tableSchema, result, dropIfExists);

                    if (dryRun) {
                        writer.printDDL(fullName, ddl);
                    } else {
                        writer.executeDDL(tidbConn, ddl, result);
                    }
                } catch (Exception e) {
                    result.setError(e.getMessage());
                }

                switch (result.getStatus()) {
                    case OK -> { log("INFO", progress + " Converting table " + fullName + " ... OK"); succeeded++; }
                    case WARN -> {
                        for (String w : result.getWarnings()) log("WARN", progress + " Converting table " + fullName + " ... " + w);
                        warned++;
                    }
                    case ERROR -> {
                        log("ERROR", progress + " Converting table " + fullName + " ... " + result.getErrorMessage());
                        failed++;
                        if (!continueOnError) break;
                    }
                }
            }

            if (tidbConn != null) tidbConn.close();

            log("INFO", "Conversion completed: " + succeeded + " succeeded, " + warned + " warnings, " + failed + " failed");
            if (failed > 0) System.exit(1);
        }
    }

    private void log(String level, String message) {
        System.out.printf("[%-5s] %s%n", level, message);
    }
}
