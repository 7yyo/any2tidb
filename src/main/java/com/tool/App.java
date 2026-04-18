package com.tool;

import com.tool.config.AppConfig;
import com.tool.converter.SchemaConverter;
import com.tool.extractor.SqlServerExtractor;
import com.tool.model.TableSchema;
import com.tool.verifier.SchemaVerifier;
import com.tool.verifier.VerifyResult;
import com.tool.writer.TiDBWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
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
    private final SchemaVerifier verifier;

    public App(AppConfig config, SqlServerExtractor extractor, SchemaConverter converter,
               TiDBWriter writer, SchemaVerifier verifier) {
        this.config = config;
        this.extractor = extractor;
        this.converter = converter;
        this.writer = writer;
        this.verifier = verifier;
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
        int[] failedRef = {0};
        try (Connection ssConn = DriverManager.getConnection(
                config.getSqlserver().sqlServerJdbcUrl(),
                config.getSqlserver().getUsername(),
                config.getSqlserver().getPassword());
             Connection tidbConn = dryRun ? null : openTiDB()) {

            List<String[]> tableList = extractor.listTables(ssConn, schemas, tables);
            log("INFO", "Starting conversion, found " + tableList.size() + " tables");

            int succeeded = 0, warned = 0, failed = 0;
            List<String[]> succeededTables = new ArrayList<>();
            boolean stopEarly = false;

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
                    case OK -> { log("INFO", progress + " Converting table " + fullName + " ... OK"); succeeded++; succeededTables.add(entry); }
                    case WARN -> {
                        for (String w : result.getWarnings()) log("WARN", progress + " Converting table " + fullName + " ... " + w);
                        warned++;
                        succeededTables.add(entry);
                    }
                    case ERROR -> {
                        log("ERROR", progress + " Converting table " + fullName + " ... " + result.getErrorMessage());
                        failed++;
                        if (!continueOnError) { stopEarly = true; }
                    }
                }
                if (stopEarly) break;
            }

            log("INFO", "Conversion completed: " + succeeded + " succeeded, " + warned + " warnings, " + failed + " failed");

            // Schema verify（dry-run 时跳过，因为没有 tidbConn）
            if (!dryRun && tidbConn != null && !succeededTables.isEmpty()) {
                printVerifyTable(tidbConn, ssConn, succeededTables);
            }

            failedRef[0] = failed;
        }
        // Exit after connections are closed (try-with-resources has already cleaned up)
        if (failedRef[0] > 0) System.exit(1);
    }

    private Connection openTiDB() throws Exception {
        log("INFO", "Connecting to TiDB...");
        return DriverManager.getConnection(
                config.getTidb().tidbJdbcUrl(),
                config.getTidb().getUsername(),
                config.getTidb().getPassword());
    }

    private void printVerifyTable(Connection tidbConn, Connection msConn, List<String[]> tables) {
        List<VerifyResult> results;
        try {
            results = verifier.verifyAll(msConn, tidbConn, tables);
        } catch (Exception e) {
            log("ERROR", "[VERIFY] failed to run schema checksum: " + e.getMessage());
            return;
        }

        // 计算表名列宽（最短 25）
        int nameWidth = results.stream()
                .mapToInt(r -> r.fullTableName().length())
                .max().orElse(10);
        nameWidth = Math.max(nameWidth, 25);

        String fmt = "[VERIFY] %-" + nameWidth + "s  %-8s  %-7s  %-7s  %-7s  %-7s  %-7s  %-7s%n";
        String detailIndent = " ".repeat(9 + nameWidth + 2);  // align with STATUS column

        System.out.printf(fmt, "TABLE", "STATUS", "COLS", "PK", "IDX", "FK(MS)", "NOTNULL", "AI");

        for (VerifyResult r : results) {
            String status = r.isMismatch() ? "MISMATCH" : "OK";
            String cols    = r.msCols()    + "/" + r.tidbCols();
            String pk      = r.msPkCols().size() + "/" + r.tidbPkCols().size();
            String idx     = r.msIdx()     + "/" + r.tidbIdx();
            String fk      = r.msFk()      + "/0";
            String notnull = r.msNotNull() + "/" + r.tidbNotNull();
            String ai      = (r.msAiCol() != null ? "1" : "0") + "/" + (r.tidbAiCol() != null ? "1" : "0");

            System.out.printf(fmt, r.fullTableName(), status, cols, pk, idx, fk, notnull, ai);

            if (r.isMismatch()) {
                for (String line : r.diffLines()) {
                    System.out.println(detailIndent + "└─ " + line);
                }
            }
        }
    }

    private void log(String level, String message) {
        System.out.printf("[%-5s] %s%n", level, message);
    }
}
