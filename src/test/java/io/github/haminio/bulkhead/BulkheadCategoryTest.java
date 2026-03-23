package io.github.haminio.bulkhead;

import org.junit.jupiter.api.Test;

import static io.github.haminio.bulkhead.BulkheadCategory.Category.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkheadCategoryTest {

    private final BulkheadCategory bulkhead = new BulkheadCategory(5 * 1024 * 1024L); // 5 MB

    @Test
    void smallPayloadRoutesToSmallPool() {
        assertEquals(SMALL, bulkhead.classify(1024));               // 1 KB
        assertEquals(SMALL, bulkhead.classify(5 * 1024 * 1024 - 1)); // just under threshold
    }

    @Test
    void exactThresholdRoutesToLargePool() {
        assertEquals(LARGE, bulkhead.classify(5 * 1024 * 1024L));   // exactly 5 MB
    }

    @Test
    void largePayloadRoutesToLargePool() {
        assertEquals(LARGE, bulkhead.classify(50L * 1024 * 1024 * 1024)); // 50 GB
    }

    @Test
    void unknownSizeConservativelyRoutesToLargePool() {
        assertEquals(LARGE, bulkhead.classify(-1));   // unknown Content-Length
        assertEquals(LARGE, bulkhead.classify(-100)); // any negative = unknown
    }
}
