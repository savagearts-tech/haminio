package io.github.haminio.endpoint;

import java.util.List;

/**
 * Tracks the health state of all registered MinIO endpoints.
 * <p>
 * Health probing is done by calling each endpoint's {@code /minio/health/ready}
 * via a plain HTTP GET — no MinIO SDK methods are used for probing.
 */
public interface EndpointManager {

    /** Returns all currently healthy endpoints. */
    List<Endpoint> healthyEndpoints();

    /** Marks an endpoint as failed (passive downgrade). */
    void markFailed(Endpoint endpoint);

    /** Marks an endpoint as recovered. */
    void markRecovered(Endpoint endpoint);

    /** Starts the background health-check scheduling loop. */
    void start();

    /** Shuts down the background scheduler gracefully. */
    void stop();
}
