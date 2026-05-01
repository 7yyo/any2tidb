package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.pipeline.MigrationStep;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.source.SourceDriver;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Pre-flight validation: checks that source and target hosts are configured
 * and reachable. When consistency mode is enabled, also checks source edition
 * prerequisites.
 *
 * All runtime parameters (databases, tables, dropIfExists, continueOnError)
 * are already set in StepContext by App.java from CLI flags.
 *
 * Reads from context:
 *   "dryRun"          (Boolean) — normalizes to non-null boolean
 *   "dumpSnapshot"    (Boolean) — triggers edition check
 */
public class PreCheckStep implements MigrationStep {

    private final AppConfig config;
    private final SourceDriver sourceDriver;

    public PreCheckStep(AppConfig config, SourceDriver sourceDriver) {
        this.config = config;
        this.sourceDriver = sourceDriver;
    }

    @Override
    public String name() { return "PreCheck"; }

    @Override
    public StepResult execute(StepContext ctx) {
        String srcHost = config.getSource().getHost();
        if (srcHost == null || srcHost.isBlank()) {
            return StepResult.fatal("source.host is not configured in application.yml");
        }
        String tgtHost = config.getTarget().getHost();
        if (tgtHost == null || tgtHost.isBlank()) {
            return StepResult.fatal("target.host is not configured in application.yml");
        }

        // Quick TCP pre-check first (instant for unreachable hosts)
        int srcPort = config.getSource().getPort();
        if (!isReachable(srcHost, srcPort)) {
            return StepResult.fatal("Cannot reach source database at " + srcHost + ":" + srcPort);
        }

        // Real JDBC login check (catches non-SQL-Server services on the port)
        String jdbcError = tryJdbcLogin();
        if (jdbcError != null) {
            return StepResult.fatal("Cannot connect to source database at " + srcHost + ":" + srcPort
                    + " — " + jdbcError);
        }

        int tgtPort = config.getTarget().getPort();
        if (!isReachable(tgtHost, tgtPort)) {
            return StepResult.fatal("Cannot reach TiDB at " + tgtHost + ":" + tgtPort);
        }

        // Normalize dryRun to non-null boolean
        Boolean dryRun = ctx.get("dryRun", Boolean.class);
        ctx.put("dryRun", dryRun != null && dryRun);

        // Edition check for consistency mode
        boolean useSnapshot = Boolean.TRUE.equals(ctx.get("dumpSnapshot", Boolean.class));
        if (useSnapshot && sourceDriver != null) {
            try (Connection c = DriverManager.getConnection(
                    sourceDriver.buildJdbcUrl(config.getSource()),
                    config.getSource().getUsername(),
                    config.getSource().getPassword())) {
                sourceDriver.consistencyProvider().checkPrerequisites(c);
            } catch (Exception e) {
                return StepResult.fatal(e.getMessage());
            }
        }

        return StepResult.ok("config validated");
    }

    /** Attempt a JDBC login to verify the source database is reachable. Returns error message or null. */
    protected String tryJdbcLogin() {
        try {
            DriverManager.setLoginTimeout(5);
            try (Connection c = DriverManager.getConnection(
                    sourceDriver.buildJdbcUrl(config.getSource()),
                    config.getSource().getUsername(),
                    config.getSource().getPassword())) {
                // connection succeeded, nothing more needed
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
