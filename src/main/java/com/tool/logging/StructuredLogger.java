package com.tool.logging;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes structured log lines to a file (append mode).
 * Format: [timestamp] [LEVEL] ["message"] [key=value] ...
 * All writes go to the file only — never to stdout.
 */
public class StructuredLogger implements AutoCloseable {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS Z");

    private final PrintWriter out;

    /** Open (or create) the given log file in append mode. */
    public static StructuredLogger open(String path) {
        try {
            return new StructuredLogger(new PrintWriter(new FileWriter(path, true), true));
        } catch (Exception e) {
            // Fall back to /dev/null — logging is best-effort
            return new StructuredLogger(new PrintWriter(new java.io.OutputStream() {
                public void write(int b) {}
            }));
        }
    }

    private StructuredLogger(PrintWriter out) {
        this.out = out;
    }

    /**
     * Write one log line.
     * @param level   e.g. "INFO", "WARN", "ERROR"
     * @param message free-text message
     * @param fields  alternating key-value pairs (must be even count)
     */
    public void log(String level, String message, Object... fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ZonedDateTime.now().format(TS)).append(']');
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
