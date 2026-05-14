/*
 * Copyright (c) 2026. Optimized implementation.
 */

package com.stip.net.optimized;

import java.util.List;

/**
 * Batch processor interface for chunk-oriented batch operations
 * Inspired by Spring Batch chunk-oriented processing model
 * 
 * @param <I> Input element type
 * @param <O> Output element type
 */
@FunctionalInterface
public interface BatchProcessor<I, O> {
    /**
     * Process a batch of items
     * 
     * @param items input items to process
     * @return processed output items
     */
    List<O> process(List<I> items);
    
    /**
     * Called before batch processing starts
     * Default implementation does nothing
     */
    default void onBatchStart() {}
    
    /**
     * Called after batch processing completes
     * Default implementation does nothing
     */
    default void onBatchEnd() {}
    
    /**
     * Called when batch processing fails
     * Default implementation does nothing
     * 
     * @param error the error that occurred
     */
    default void onBatchError(Throwable error) {}
}
