package com.tool.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Command(name = "snapshot", mixinStandardHelpOptions = true,
        description = "Export data directly to TiDB via Debezium CDC")
public class SnapshotCommand implements Runnable {

    @Option(names = "--tables", split = ",", paramLabel = "TABLE",
            description = "Only process specified tables (default: all)")
    public List<String> tables;

    @Option(names = "--dbs", split = ",", paramLabel = "DB",
            description = "Only process specified databases (default: all)")
    public List<String> databases;

    @Option(names = "--batch-size", paramLabel = "N", defaultValue = "5000",
            description = "INSERT batch size (default: 5000)")
    public int batchSize;

    @Option(names = "--fetch-size", paramLabel = "N", defaultValue = "2000",
            description = "Debezium snapshot fetch size (default: 2000)")
    public int fetchSize;

    @Option(names = "--snapshot-threads", paramLabel = "N", defaultValue = "1",
            description = "Debezium parallel chunk threads (default: 1)")
    public int snapshotThreads;

    @Option(names = "--auto-enable-cdc",
            description = "Automatically enable CDC on SQL Server")
    public boolean autoEnableCdc;

    @Override
    public void run() {}
}
