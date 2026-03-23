package io.github.haminio.observability;

import io.github.haminio.endpoint.Endpoint;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

import java.util.List;
import java.util.Map;

/**
 * Registers Micrometer gauges that expose OkHttp {@link ConnectionPool} state.
 * <p>
 * Per architectural constraint C4: pool metrics MUST be read directly from
 * {@code OkHttpClient.connectionPool()} public API — no interceptors, no reflection.
 */
public class ConnectionPoolMetrics {

    private ConnectionPoolMetrics() {}

    /**
     * Binds SMALL and LARGE pool gauges to the given {@link MeterRegistry}.
     *
     * @param registry  Micrometer registry
     * @param smallClient  OkHttpClient used for small requests
     * @param largeClient  OkHttpClient used for large/streaming requests
     */
    public static void bind(MeterRegistry registry, OkHttpClient smallClient, OkHttpClient largeClient) {
        bindPool(registry, "SMALL", smallClient.connectionPool());
        bindPool(registry, "LARGE", largeClient.connectionPool());
    }

    private static void bindPool(MeterRegistry registry, String category, ConnectionPool pool) {
        Gauge.builder("minio.client.pool.idle.connections",
                        pool, ConnectionPool::idleConnectionCount)
                .tag("pool", category)
                .description("Number of idle connections in the OkHttp connection pool")
                .register(registry);

        Gauge.builder("minio.client.pool.total.connections",
                        pool, ConnectionPool::connectionCount)
                .tag("pool", category)
                .description("Total number of connections in the OkHttp connection pool")
                .register(registry);
    }

    /**
     * Registers a health gauge (1=healthy/CLOSED, 0=unhealthy/OPEN) for each endpoint.
     */
    public static void bindEndpointHealth(MeterRegistry registry,
                                           List<Endpoint> endpoints,
                                           io.github.haminio.endpoint.EndpointManager manager) {
        for (Endpoint ep : endpoints) {
            Gauge.builder("minio.client.endpoint.healthy",
                            ep,
                            e -> manager.healthyEndpoints().contains(e) ? 1.0 : 0.0)
                    .tag("endpoint", ep.url())
                    .description("1 if endpoint circuit is CLOSED, 0 if OPEN")
                    .register(registry);
        }
    }
}
