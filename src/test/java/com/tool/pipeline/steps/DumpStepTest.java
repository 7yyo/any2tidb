package com.tool.pipeline.steps;

import com.tool.config.AppConfig;
import com.tool.dump.extractor.DumpExtractor;
import com.tool.dump.extractor.RowBatch;
import com.tool.dump.writer.DumpWriter;
import com.tool.logging.StructuredLogger;
import com.tool.pipeline.StepContext;
import com.tool.pipeline.StepResult;
import com.tool.schema.extractor.SchemaExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DumpStepTest {

    @TempDir
    Path tmp;

    private SchemaExtractor schemaExtractor;
    private DumpExtractor dumpExtractor;
    private DumpWriter dumpWriter;
    private StructuredLogger log;
    private AppConfig config;
    private StepContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        schemaExtractor = mock(SchemaExtractor.class);
        dumpExtractor   = mock(DumpExtractor.class);
        dumpWriter      = mock(DumpWriter.class);
        log             = mock(StructuredLogger.class);

        config = new AppConfig();
        AppConfig.DbConfig src = new AppConfig.DbConfig();
        src.setHost("127.0.0.1"); src.setPort(1433);
        src.setUsername("sa"); src.setPassword("pw");
        config.setSource(src);
        AppConfig.ConvertConfig cc = new AppConfig.ConvertConfig();
        cc.setSchemas(List.of()); cc.setTables(List.of());
        config.setConvert(cc);
        AppConfig.DumpConfig dc = new AppConfig.DumpConfig();
        dc.setOutputDir(tmp.toString());
        dc.setConcurrency(1);
        config.setDump(dc);

        ctx = new StepContext();
        ctx.put("schemas", List.of());
        ctx.put("tables",  List.of());
    }

    @Test
    void emptyDatabase_noTablesListed_returnsOk() throws Exception {
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        assertFalse(r.isFatal());
        verify(dumpWriter, never()).writeBatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void singleTable_streamTableCalled() throws Exception {
        Connection mockConn = mock(Connection.class);
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of("testdb"));
        when(schemaExtractor.listTables(any(), any(), any()))
                .thenReturn(List.<String[]>of(new String[]{"dbo", "orders"}));
        when(dumpExtractor.getColumnNames(any(), eq("dbo"), eq("orders")))
                .thenReturn(List.of("id", "amount"));
        when(dumpExtractor.estimateRowCount(any(), any(), any())).thenReturn(100L);

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        StepResult r = step.executeWithConnections(ctx, dbName -> mockConn);

        assertFalse(r.isFatal());
        verify(dumpExtractor).streamTable(eq(mockConn), eq("dbo"), eq("orders"),
                anyInt(), any());
        verify(dumpWriter).close();
    }

    @Test
    void contextContainsDumpSummaries_afterExecution() throws Exception {
        when(schemaExtractor.listDatabases(any())).thenReturn(List.of());
        when(schemaExtractor.listTables(any(), any(), any())).thenReturn(List.of());

        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        step.executeWithConnections(ctx, dbName -> mock(Connection.class));

        assertNotNull(ctx.get("dumpSummaries", List.class));
    }

    @Test
    void stepName_isDump() {
        DumpStep step = new DumpStep(config, schemaExtractor, dumpExtractor,
                () -> dumpWriter, log);
        assertEquals("Dump", step.name());
    }
}
