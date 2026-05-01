package com.tool.sync;

import com.tool.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tool.snapshot.sink.SinkRecordConverter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SyncWriter {

    private final SinkRecordConverter converter;
    private static final Logger log = LoggerFactory.getLogger(SyncWriter.class);

    public SyncWriter(SinkRecordConverter converter) {
        this.converter = converter;
    }

    public void insert(Connection conn, String dbName, String table,
                       List<String> colNames, Map<String, Object> after) {
        String cols = colNames.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
        String placeholders = colNames.stream().map(c -> "?").collect(Collectors.joining(", "));
        String updatePart = colNames.stream()
                .map(c -> "`" + c + "` = VALUES(`" + c + "`)")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + dbName + "`.`" + table + "` (" + cols + ") VALUES ("
                + placeholders + ") ON DUPLICATE KEY UPDATE " + updatePart;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < colNames.size(); i++) {
                converter.bindSingle(ps, i + 1, dbName, table, colNames.get(i), after.get(colNames.get(i)));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("sync INSERT failed: " + dbName + "." + table, e);
        }
    }

    public void update(Connection conn, String dbName, String table,
                       Map<String, Object> pkMap, Map<String, Object> after) {
        List<String> pkCols = new ArrayList<>(pkMap.keySet());
        List<String> setCols = after.keySet().stream()
                .filter(c -> !pkMap.containsKey(c))
                .collect(Collectors.toList());
        if (setCols.isEmpty()) return;

        String setPart = setCols.stream().map(c -> "`" + c + "` = ?").collect(Collectors.joining(", "));
        String wherePart = pkCols.stream().map(c -> "`" + c + "` = ?").collect(Collectors.joining(" AND "));
        String sql = "UPDATE `" + dbName + "`.`" + table + "` SET " + setPart + " WHERE " + wherePart;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String col : setCols) {
                converter.bindSingle(ps, idx++, dbName, table, col, after.get(col));
            }
            for (String col : pkCols) {
                converter.bindSingle(ps, idx++, dbName, table, col, pkMap.get(col));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("sync UPDATE failed: " + dbName + "." + table, e);
        }
    }

    public void delete(Connection conn, String dbName, String table,
                       Map<String, Object> pkMap, Map<String, Object> before) {
        Map<String, Object> whereMap = (pkMap != null && !pkMap.isEmpty()) ? pkMap : before;
        if (whereMap == null || whereMap.isEmpty()) return;
        List<String> whereCols = new ArrayList<>(whereMap.keySet());

        String wherePart = whereCols.stream().map(c -> "`" + c + "` = ?").collect(Collectors.joining(" AND "));
        String sql = "DELETE FROM `" + dbName + "`.`" + table + "` WHERE " + wherePart;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String col : whereCols) {
                converter.bindSingle(ps, idx++, dbName, table, col, whereMap.get(col));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("sync DELETE failed: " + dbName + "." + table, e);
        }
    }
}
