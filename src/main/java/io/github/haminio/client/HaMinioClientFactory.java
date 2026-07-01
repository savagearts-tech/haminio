package io.github.haminio.client;

import io.github.haminio.bulkhead.BulkheadCategory;
import io.github.haminio.endpoint.DefaultEndpointManager;
import io.github.haminio.endpoint.EndpointManager;
import io.github.haminio.interceptor.FailoverInterceptor;
import io.github.haminio.interceptor.LoadBalancingInterceptor;
import io.github.haminio.interceptor.MultipartSessionRegistry;
import io.github.haminio.observability.ConnectionPoolMetrics;
import io.github.haminio.observability.MinioEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

/**
 * Factory that wires all components and produces a configured {@link HaMinioClient}.
 * <p>
 * Architecture:
 * - Two separate OkHttpClient instances (SMALL and LARGE) with independent ConnectionPools.
 *   All connection lifecycle is delegated to OkHttp — no custom pool is implemented here.
 * - Each OkHttpClient is injected into its corresponding MinioClient via builder.
 * - Interceptor chain (per client): LoadBalancingInterceptor → FailoverInterceptor.
 * - EventListener is set on each OkHttpClient for I/O metrics (constraint C3).
 * - ConnectionPool gauges are registered from pool public API (constraint C4).
 */
public class HaMinioClientFactory {

    private HaMinioClientFactory() {}

    private static LoadBalancer createLoadBalancer(
            EndpointManager endpointManager, LoadBalancingStrategy strategy) {
        return switch (strategy) {
            case ROUND_ROBIN -> new RoundRobinLoadBalancer(endpointManager);
            case RANDOM -> new RandomLoadBalancer(endpointManager);
        };
    }

    private record SharedInfrastructure(
            OkHttpClient smallClient,
            OkHttpClient largeClient,
            EndpointManager endpointManager,
            String primaryUrl
    ) {}

    private static SharedInfrastructure buildShared(HaMinioClientConfig config, MeterRegistry registry) {
        // ── 1. Endpoint Manager (health tracking + circuit breakers) ──────────
        DefaultEndpointManager endpointManager = new DefaultEndpointManager(
                config.endpoints(),
                config.circuitBreakerFailureThreshold(),
                config.circuitBreakerRecoveryThreshold(),
                config.healthCheckInterval()
        );

        // ── 2. Load Balancer ──────────────────────────────────────────────────
        MultipartSessionRegistry sessionRegistry = new MultipartSessionRegistry();
        LoadBalancer loadBalancer = createLoadBalancer(endpointManager, config.loadBalancingStrategy());

        // ── 3. Shared interceptors ────────────────────────────────────────────
        LoadBalancingInterceptor lbInterceptor =
                new LoadBalancingInterceptor(loadBalancer, sessionRegistry);
        FailoverInterceptor failoverInterceptor =
                new FailoverInterceptor(endpointManager, config.maxRetries(), config.retryBaseDelay());

        // ── 4. SMALL OkHttpClient (delegates pool to OkHttp ConnectionPool) ───
        String primaryUrl = config.endpoints().get(0).url();

        ConnectionPool smallPool = new ConnectionPool(
                config.smallPoolMaxIdleConnections(),
                config.smallPoolKeepAliveDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
        OkHttpClient smallClient = new OkHttpClient.Builder()
                .connectionPool(smallPool)          // C1: delegate to OkHttp pool
                .eventListenerFactory(MinioEventListener.factory(registry, primaryUrl + "/SMALL")) // C3
                .addInterceptor(lbInterceptor)
                .addInterceptor(failoverInterceptor)
                .build();

        // ── 5. LARGE OkHttpClient (separate ConnectionPool for Bulkhead) ──────
        ConnectionPool largePool = new ConnectionPool(
                config.largePoolMaxIdleConnections(),
                config.largePoolKeepAliveDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
        OkHttpClient largeClient = new OkHttpClient.Builder()
                .connectionPool(largePool)          // C1: delegate to OkHttp pool
                .eventListenerFactory(MinioEventListener.factory(registry, primaryUrl + "/LARGE")) // C3
                .addInterceptor(lbInterceptor)
                .addInterceptor(failoverInterceptor)
                .readTimeout(java.time.Duration.ofHours(2)) // long timeout for streaming uploads
                .writeTimeout(java.time.Duration.ofHours(2))
                .build();

        // ── 6. Observability: ConnectionPool gauges (C4) + endpoint health ────
        ConnectionPoolMetrics.bind(registry, smallClient, largeClient);
        ConnectionPoolMetrics.bindEndpointHealth(registry, config.endpoints(), endpointManager);

        return new SharedInfrastructure(smallClient, largeClient, endpointManager, primaryUrl);
    }

    public static HaMinioClient create(HaMinioClientConfig config) {
        return create(config, new SimpleMeterRegistry());
    }

    public static HaMinioClient create(HaMinioClientConfig config, MeterRegistry registry) {
        SharedInfrastructure shared = buildShared(config, registry);

        MinioClient smallMinioClient = MinioClient.builder()
                .endpoint(shared.primaryUrl())
                .credentials(config.accessKey(), config.secretKey())
                .httpClient(shared.smallClient())
                .build();

        MinioClient largeMinioClient = MinioClient.builder()
                .endpoint(shared.primaryUrl())
                .credentials(config.accessKey(), config.secretKey())
                .httpClient(shared.largeClient())
                .build();

        BulkheadCategory bulkhead = new BulkheadCategory(config.bulkheadSizeThresholdBytes());
        shared.endpointManager().start();

        return new HaMinioClient(smallMinioClient, largeMinioClient, bulkhead, shared.endpointManager());
    }

    public static HaMinioAsyncClient createAsync(HaMinioClientConfig config) {
        return createAsync(config, new SimpleMeterRegistry());
    }

    public static HaMinioAsyncClient createAsync(HaMinioClientConfig config, MeterRegistry registry) {
        SharedInfrastructure shared = buildShared(config, registry);

        MinioAsyncClient smallAsyncClient = MinioAsyncClient.builder()
                .endpoint(shared.primaryUrl())
                .credentials(config.accessKey(), config.secretKey())
                .httpClient(shared.smallClient())
                .build();

        MinioAsyncClient largeAsyncClient = MinioAsyncClient.builder()
                .endpoint(shared.primaryUrl())
                .credentials(config.accessKey(), config.secretKey())
                .httpClient(shared.largeClient())
                .build();

        BulkheadCategory bulkhead = new BulkheadCategory(config.bulkheadSizeThresholdBytes());
        shared.endpointManager().start();

        return new HaMinioAsyncClient(smallAsyncClient, largeAsyncClient, bulkhead, shared.endpointManager());
    }
}
