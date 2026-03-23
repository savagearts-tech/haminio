package io.github.haminio.client;

import io.github.haminio.endpoint.Endpoint;

import java.util.List;

/**
 * Selects the next healthy endpoint from the registered pool
 * using a defined routing algorithm (e.g., Round Robin).
 * <p>
 * This interface deliberately knows nothing about connection management;
 * all TCP connection reuse is delegated to OkHttpClient's ConnectionPool.
 */
public interface LoadBalancer {

    /**
     * Returns the next healthy endpoint or {@code null} if none are available.
     */
    Endpoint next();

    /**
     * Returns the next healthy endpoint excluding the given ones (used for failover).
     */
    Endpoint next(List<Endpoint> exclude);
}
