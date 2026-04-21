package com.tool.snapshot;

import com.tool.config.AppConfig;
import com.tool.pipeline.StepContext;
import com.tool.snapshot.model.SnapshotDbResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotStepTest {

    private AppConfig config;
    private StepContext ctx;
    private DataSource ds;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost("127.0.0.1"); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        config.setSource(src);

        AppConfig.DbConfig tgt = new AppConfig.DbConfig();
        tgt.setHost("127.0.0.1"); tgt.setPort(4000);
        tgt.setUsername("root"); tgt.setPassword("");
        config.setTarget(tgt);

        ctx = new StepContext();
        ctx.put("tables", List.of());
        ds = mock(DataSource.class);
    }

    @Test
    void stepName_isSnapshot() {
        SnapshotStep step = new SnapshotStep(config, ds);
        assertEquals("Snapshot", step.name());
    }

    @Test
    void snapshotConfig_withOverrides() {
        ctx.put("batchSize", 1000);
        ctx.put("fetchSize", 5000);
        ctx.put("snapshotThreads", 4);

        Integer batchSize = ctx.get("batchSize", Integer.class);
        Integer fetchSize = ctx.get("fetchSize", Integer.class);
        Integer threads = ctx.get("snapshotThreads", Integer.class);

        assertEquals(1000, batchSize);
        assertEquals(5000, fetchSize);
        assertEquals(4, threads);
    }

    @Test
    void snapshotDbResult_shouldBlockPipeline() {
        assertTrue(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", null, null, 0, 0L,
                        java.time.Instant.now(), "CDC not enabled"))));
    }

    @Test
    void snapshotDbResult_noBlockOnSuccess() {
        assertFalse(SnapshotDbResult.shouldBlockPipeline(List.of(
                new SnapshotDbResult("db1", "lsn", "lsn", 5, 100L,
                        java.time.Instant.now(), null))));
    }
}
