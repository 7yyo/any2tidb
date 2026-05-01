package com.tool.source.sqlserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * SQL Server CDC LSN utilities — capture and format for Debezium compatibility.
 */
public final class SqlServerCdcUtils {

    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcUtils.class);

    private SqlServerCdcUtils() {}

    /**
     * Ensure CDC is enabled on the database and capture the current max LSN.
     * Returns the LSN as a hex string (e.g. {@code 0x0000003a000001760001}).
     */
    public static String captureLsn(String host, int port, String user, String password, String dbName)
            throws Exception {
        String url = String.format(
                "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5",
                host, port, dbName);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            // 1. Enable CDC at database level if not already enabled
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()")) {
                if (rs.next() && rs.getInt(1) != 1) {
                    Log.info(log, "CDC not enabled, auto-enabling", "database", dbName);
                    try (Statement st2 = c.createStatement()) {
                        st2.execute("EXEC sys.sp_cdc_enable_db");
                    }
                    Log.info(log, "CDC enabled", "database", dbName);
                }
            }

            // 2. Capture max LSN
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT CONVERT(VARCHAR(MAX), sys.fn_cdc_get_max_lsn(), 1)")) {
                if (rs.next()) {
                    String lsn = rs.getString(1);
                    if (lsn != null && !lsn.isBlank()) {
                        return lsn;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Failed to capture LSN for database '" + dbName +
                "'. CDC may not be properly enabled. Verify sysadmin privileges.");
    }

    /**
     * Convert SQL Server hex LSN to Debezium colon-delimited format.
     * Input:  {@code 0x0000003a000001760001}
     * Output: {@code 0000003a:00000176:0001}
     */
    public static String hexLsnToDebezium(String hexLsn) {
        if (hexLsn == null || hexLsn.length() < 22) return hexLsn;
        String hex = hexLsn.startsWith("0x") ? hexLsn.substring(2) : hexLsn;
        if (hex.length() < 20) return hexLsn;
        return hex.substring(0, 8) + ":" + hex.substring(8, 16) + ":" + hex.substring(16, 20);
    }

    /**
     * Write a Debezium-compatible offset "seed" file. Used after a NOLOCK dump
     * to record the pre-dump LSN. The format matches Debezium's serialized
     * {@code HashMap<byte[], byte[]>} so SyncStep can read and verify it.
     */
    public static void writeDebeziumOffset(String path, String dbName, String debeziumLsn)
            throws IOException {
        String keyJson = "[\"any2tidb_" + dbName + "\",{\"server\":\"any2tidb_"
                + dbName + "\",\"database\":\"" + dbName + "\"}]";
        String valueJson = "{\"commit_lsn\":\"" + debeziumLsn
                + "\",\"snapshot_completed\":true}";

        HashMap<byte[], byte[]> map = new HashMap<>();
        map.put(keyJson.getBytes(StandardCharsets.UTF_8),
                valueJson.getBytes(StandardCharsets.UTF_8));

        new File(path).getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(path);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(map);
        }
    }

    /**
     * Read a Debezium offset file, replace the {@code commit_lsn} in every entry
     * that contains {@code dbName} in its key, and write the file back.
     * This preserves all other offset fields that Debezium wrote (change_lsn,
     * event_serial_no, transaction_id, etc.) — only the LSN is patched.
     */
    @SuppressWarnings("unchecked")
    public static void patchOffsetLsn(String path, String dbName, String newDebeziumLsn)
            throws Exception {
        File file = new File(path);
        if (!file.exists() || file.length() == 0) return;

        Map<Object, Object> map;
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            map = (Map<Object, Object>) ois.readObject();
        }

        ObjectMapper mapper = new ObjectMapper();
        boolean patched = false;
        for (var entry : map.entrySet()) {
            String keyJson = decodeOffsetEntry(entry.getKey());
            if (keyJson == null || !keyJson.contains(dbName)) continue;

            String valueJson = decodeOffsetEntry(entry.getValue());
            if (valueJson == null) continue;

            JsonNode root = mapper.readTree(valueJson);
            if (!root.has("commit_lsn")) continue;

            String oldLsn = root.get("commit_lsn").asText();
            ((ObjectNode) root).put("commit_lsn", newDebeziumLsn);
            String newValueJson = mapper.writeValueAsString(root);

            // Replace the byte[] or ByteBuffer entry
            byte[] newValue = newValueJson.getBytes(StandardCharsets.UTF_8);
            if (entry.getValue() instanceof ByteBuffer) {
                map.put(entry.getKey(), ByteBuffer.wrap(newValue));
            } else {
                map.put(entry.getKey(), newValue);
            }

            Log.info(log, "offset LSN patched", "database", dbName, "old", oldLsn, "new", newDebeziumLsn);
            patched = true;
            break;
        }

        if (patched) {
            try (FileOutputStream fos = new FileOutputStream(file);
                 ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                oos.writeObject(map);
            }
        }
    }

    private static String decodeOffsetEntry(Object entry) {
        if (entry instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (entry instanceof ByteBuffer bb) {
            return StandardCharsets.UTF_8.decode(bb).toString();
        }
        return null;
    }
}
