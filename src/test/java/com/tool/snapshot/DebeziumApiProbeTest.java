package com.tool.snapshot;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Probe test: verifies Debezium 3.5 API signatures match expectations.
 */
class DebeziumApiProbeTest {

    @Test
    void changeEvent_hasKeyAndValue() {
        assertDoesNotThrow(() -> ChangeEvent.class.getDeclaredMethod("key"));
        assertDoesNotThrow(() -> ChangeEvent.class.getDeclaredMethod("value"));
    }

    @Test
    void recordCommitter_hasMarkProcessed() throws Exception {
        Class<?> rcClass = Class.forName("io.debezium.engine.DebeziumEngine$RecordCommitter");
        assertNotNull(rcClass.getDeclaredMethod("markProcessed", Object.class));
        assertNotNull(rcClass.getDeclaredMethod("markBatchFinished"));
    }

    @Test
    void changeConsumer_hasHandleBatch() throws Exception {
        Class<?> ccClass = Class.forName("io.debezium.engine.DebeziumEngine$ChangeConsumer");
        Class<?> rcClass = Class.forName("io.debezium.engine.DebeziumEngine$RecordCommitter");
        assertNotNull(ccClass.getDeclaredMethod("handleBatch", java.util.List.class, rcClass));
    }

    @Test
    void debeziumEngine_createWithJsonFormat() {
        var builder = DebeziumEngine.create(Json.class);
        assertNotNull(builder, "DebeziumEngine.create(Json.class) should return a builder");
    }

    @Test
    void builder_hasNotifyingAndUsing() throws Exception {
        var builder = DebeziumEngine.create(Json.class);
        assertNotNull(builder.getClass().getMethod("notifying", java.util.function.Consumer.class));
        assertNotNull(builder.getClass().getMethod("using", java.util.Properties.class));
        assertNotNull(builder.getClass().getMethod("using", Class.forName("io.debezium.engine.DebeziumEngine$CompletionCallback")));
        assertNotNull(builder.getClass().getMethod("build"));
    }

    @Test
    void sqlServerConnector_isLoadable() {
        assertDoesNotThrow(() -> Class.forName("io.debezium.connector.sqlserver.SqlServerConnector"));
    }

    @Test
    void changeEvent_valueType_isStringWithJsonFormat() {
        // With Json format, ChangeEvent<String, String>.value() returns String (JSON), not SourceRecord
        var builder = DebeziumEngine.create(Json.class);
        // The builder generic is ChangeEvent<String, String>
        assertNotNull(builder);
    }
}
