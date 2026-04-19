package com.tool.logging;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes structured log lines to a file (append mode).
 * Format: [timestamp] [LEVEL] ["message"] [key=value] ...
 * All writes go to the file only — never to stdout.
 *
 * <p>Not thread-safe; call only from a single thread.</p>
 */
public class StructuredLogger implements AutoCloseable {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS Z");

    private final PrintWriter out;

    /** Open (or create) the given log file in append mode. Falls back to no-op on failure. */
    public static StructuredLogger open(String path) {
        try {
            return new StructuredLogger(new PrintWriter(new FileWriter(path, true), true));
        } catch (Exception e) {
            System.err.println("[StructuredLogger] WARNING: could not open '" + path
                    + "': " + e.getMessage() + " — falling back to no-op");
            return new StructuredLogger(new PrintWriter(PrintWriter.nullWriter()));
        }
    }

    private StructuredLogger(PrintWriter out) {
        this.out = out;
    }

    /**
     * Write one log line.
     *
     * @param level   e.g. "INFO", "WARN", "ERROR"
     * @param message free-text message
     * @param fields  alternating key-value pairs — must have an even number of elements
     * @throws IllegalArgumentException if {@code fields} has an odd length
     */
    public void log(String level, String message, Object... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "fields must be key-value pairs; got " + fields.length + " element(s)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ZonedDateTime.now().format(TIMESTAMP_FORMATTER)).append(']');
        sb.append(" [").append(level).append(']');
        sb.append(" [\"").append(message).append("\"]");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String k = String.valueOf(fields[i]);
            String v = String.valueOf(fields[i + 1]);
            boolean quote = v.contains(" ") || v.isEmpty();
            sb.append(" [").append(k).append('=');
            if (quote) sb.append('"').append(v).append('"');
            else       sb.append(v);
            sb.append(']');
        }
        out.println(sb);
    }

    @Override
    public void close() {
        out.close();
    }
}
