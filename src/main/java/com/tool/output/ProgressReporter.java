package com.tool.output;

/**
 * Renders an in-place progress bar on stdout using carriage return (\r).
 * Thread-unsafe — call only from the main thread.
 */
public class ProgressReporter {

    private static final int BAR_WIDTH = 20;

    private int lastProgressLen = 0;
    private int dbNameWidth = 0;

    public void setDbNameWidth(int width) {
        this.dbNameWidth = width;
    }

    /**
     * Overwrite the current console line with a progress bar.
     *
     * @param dbName    database being converted
     * @param done      tables completed so far
     * @param total     total tables in this database
     * @param current   name of the table currently being processed
     */
    public void print(String dbName, int done, int total, String current) {
        int filled = (total == 0) ? BAR_WIDTH : (int) Math.round((double) done / total * BAR_WIDTH);
        String bar = "━".repeat(filled) + "─".repeat(BAR_WIDTH - filled);
        String line = String.format(" %-" + dbNameWidth + "s  [%s]  %d/%d  %s",
                dbName, bar, done, total, current);
        if (line.length() < lastProgressLen) {
            line = line + " ".repeat(lastProgressLen - line.length());
        }
        lastProgressLen = line.length();
        System.out.print("\r" + line);
        System.out.flush();
    }

    /** Erase the progress line — call before printing multi-line output. */
    public void clear() {
        if (lastProgressLen > 0) {
            System.out.print("\r" + " ".repeat(lastProgressLen) + "\r");
            System.out.flush();
            lastProgressLen = 0;
        }
    }
}
