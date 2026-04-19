package com.tool.dump.extractor;

/**
 * Callback invoked once per batch during a streaming table read.
 * Implementations must process the batch synchronously before returning;
 * the batch is discarded after the call returns.
 */
@FunctionalInterface
public interface RowBatchConsumer {
    void accept(RowBatch batch) throws Exception;
}
