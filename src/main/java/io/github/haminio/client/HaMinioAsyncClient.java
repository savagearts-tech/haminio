package io.github.haminio.client;

import io.github.haminio.bulkhead.BulkheadCategory;
import io.github.haminio.endpoint.EndpointManager;
import io.minio.PutObjectArgs;
import io.minio.ObjectWriteResponse;
import io.minio.MinioAsyncClient;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-Availability MinIO async client façade.
 * <p>
 * Delegates asynchronous S3 protocol operations to the underlying {@link MinioAsyncClient},
 * selecting the SMALL or LARGE client pool based on Bulkhead classification.
 * <p>
 * This class does NOT extend {@link MinioAsyncClient} (composition, not inheritance).
 * It does NOT manage connections; all connection lifecycle is owned by OkHttp.
 */
public class HaMinioAsyncClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HaMinioAsyncClient.class);

    private final MinioAsyncClient smallAsyncClient;
    private final MinioAsyncClient largeAsyncClient;
    private final BulkheadCategory bulkhead;
    private final EndpointManager endpointManager;

    HaMinioAsyncClient(MinioAsyncClient smallAsyncClient,
                       MinioAsyncClient largeAsyncClient,
                       BulkheadCategory bulkhead,
                       EndpointManager endpointManager) {
        this.smallAsyncClient = smallAsyncClient;
        this.largeAsyncClient = largeAsyncClient;
        this.bulkhead = bulkhead;
        this.endpointManager = endpointManager;
    }

    // ── Object Operations (Async) ─────────────────────────────────────────────

    /**
     * Asynchronously uploads an object. Selects SMALL or LARGE async pool based on object size.
     * Unknown size (-1) conservatively routes to LARGE pool.
     */
    public CompletableFuture<ObjectWriteResponse> putObjectAsync(PutObjectArgs args) throws Exception {
        long size = args.objectSize();
        BulkheadCategory.Category category = bulkhead.classify(size);
        log.debug("putObjectAsync size={} → {} async pool", size, category);
        return category == BulkheadCategory.Category.LARGE
                ? largeAsyncClient.putObject(args)
                : smallAsyncClient.putObject(args);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        endpointManager.stop();
        log.info("HaMinioAsyncClient shut down.");
    }
}
