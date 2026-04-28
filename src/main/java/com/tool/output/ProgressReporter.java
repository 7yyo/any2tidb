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
        // Progress output suppressed — see any2tidb.log
    }

    public void clear() {
        // Progress output suppressed — see any2tidb.log
    }
}
