package io.github.haminio.client;

import io.github.haminio.bulkhead.BulkheadCategory;
import io.github.haminio.endpoint.EndpointManager;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ObjectWriteResponse;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-Availability MinIO client façade.
 * <p>
 * Delegates all S3 protocol operations to the underlying {@link MinioClient},
 * selecting the SMALL or LARGE client based on Bulkhead classification.
 * <p>
 * This class does NOT extend {@link MinioClient} (composition, not inheritance).
 * It does NOT manage connections; all connection lifecycle is owned by OkHttp.
 */
public class HaMinioClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HaMinioClient.class);

    private final MinioClient smallClient;
    private final MinioClient largeClient;
    private final BulkheadCategory bulkhead;
    private final EndpointManager endpointManager;

    HaMinioClient(MinioClient smallClient,
                  MinioClient largeClient,
                  BulkheadCategory bulkhead,
                  EndpointManager endpointManager) {
        this.smallClient = smallClient;
        this.largeClient = largeClient;
        this.bulkhead = bulkhead;
        this.endpointManager = endpointManager;
    }

    // ── Object Operations ─────────────────────────────────────────────────────

    /**
     * Downloads an object. Always uses the SMALL pool (read operations are streaming
     * but relatively short-lived compared to large uploads).
     */
    public GetObjectResponse getObject(GetObjectArgs args) throws Exception {
        return smallClient.getObject(args);
    }

    /**
     * Uploads an object. Selects SMALL or LARGE pool based on object size.
     * Unknown size (-1) conservatively routes to LARGE pool.
     */
    public ObjectWriteResponse putObject(PutObjectArgs args) throws Exception {
        long size = args.objectSize();
        BulkheadCategory.Category category = bulkhead.classify(size);
        log.debug("putObject size={} → {} pool", size, category);
        return category == BulkheadCategory.Category.LARGE
                ? largeClient.putObject(args)
                : smallClient.putObject(args);
    }

    /**
     * Stats an object. Always routes to SMALL pool (metadata-only, fast operation).
     */
    public StatObjectResponse statObject(StatObjectArgs args) throws Exception {
        return smallClient.statObject(args);
    }

    /**
     * Removes an object. Always routes to SMALL pool.
     */
    public void removeObject(RemoveObjectArgs args) throws Exception {
        smallClient.removeObject(args);
    }

    /**
     * Lists objects. Always routes to SMALL pool.
     */
    public Iterable<Result<Item>> listObjects(ListObjectsArgs args) {
        return smallClient.listObjects(args);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        endpointManager.stop();
        log.info("HaMinioClient shut down.");
    }
}
