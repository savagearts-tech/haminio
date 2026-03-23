package io.github.haminio.client;

import io.github.haminio.endpoint.Endpoint;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for {@link HaMinioClient}.
 * Use {@link Builder} to construct.
 */
public final class HaMinioClientConfig {

    // ── Endpoints ──────────────────────────────────────────────────────────
    private final List<Endpoint> endpoints;
    private final String accessKey;
    private final String secretKey;

    // ── Retry & Failover ───────────────────────────────────────────────────
    /** Maximum number of cross-endpoint retry attempts per request. */
    private final int maxRetries;

    /** Base delay for exponential backoff; jitter is added automatically. */
    private final Duration retryBaseDelay;

    // ── Health Check ───────────────────────────────────────────────────────
    /** How often each endpoint's /minio/health/ready is probed. */
    private final Duration healthCheckInterval;

    /** Number of consecutive failures before a circuit breaker opens (CLOSED → OPEN). */
    private final int circuitBreakerFailureThreshold;

    /** Number of consecutive successes required to close a circuit breaker (HALF_OPEN → CLOSED). */
    private final int circuitBreakerRecoveryThreshold;

    // ── Bulkhead ───────────────────────────────────────────────────────────
    /** Requests with Content-Length >= this threshold (or -1/unknown) go to the LARGE pool. */
    private final long bulkheadSizeThresholdBytes;

    /** Stateless request routing across healthy endpoints. */
    private final LoadBalancingStrategy loadBalancingStrategy;

    // ── Connection Pool (delegated entirely to OkHttp ConnectionPool) ──────
    private final int smallPoolMaxIdleConnections;
    private final Duration smallPoolKeepAliveDuration;
    private final int largePoolMaxIdleConnections;
    private final Duration largePoolKeepAliveDuration;

    private HaMinioClientConfig(Builder b) {
        this.endpoints = List.copyOf(b.endpoints);
        this.accessKey = b.accessKey;
        this.secretKey = b.secretKey;
        this.maxRetries = b.maxRetries;
        this.retryBaseDelay = b.retryBaseDelay;
        this.healthCheckInterval = b.healthCheckInterval;
        this.circuitBreakerFailureThreshold = b.circuitBreakerFailureThreshold;
        this.circuitBreakerRecoveryThreshold = b.circuitBreakerRecoveryThreshold;
        this.bulkheadSizeThresholdBytes = b.bulkheadSizeThresholdBytes;
        this.loadBalancingStrategy = b.loadBalancingStrategy;
        this.smallPoolMaxIdleConnections = b.smallPoolMaxIdleConnections;
        this.smallPoolKeepAliveDuration = b.smallPoolKeepAliveDuration;
        this.largePoolMaxIdleConnections = b.largePoolMaxIdleConnections;
        this.largePoolKeepAliveDuration = b.largePoolKeepAliveDuration;
    }

    public List<Endpoint> endpoints()                        { return endpoints; }
    public String accessKey()                                { return accessKey; }
    public String secretKey()                                { return secretKey; }
    public int maxRetries()                                  { return maxRetries; }
    public Duration retryBaseDelay()                         { return retryBaseDelay; }
    public Duration healthCheckInterval()                    { return healthCheckInterval; }
    public int circuitBreakerFailureThreshold()              { return circuitBreakerFailureThreshold; }
    public int circuitBreakerRecoveryThreshold()             { return circuitBreakerRecoveryThreshold; }
    public long bulkheadSizeThresholdBytes()                 { return bulkheadSizeThresholdBytes; }
    public LoadBalancingStrategy loadBalancingStrategy()     { return loadBalancingStrategy; }
    public int smallPoolMaxIdleConnections()                 { return smallPoolMaxIdleConnections; }
    public Duration smallPoolKeepAliveDuration()             { return smallPoolKeepAliveDuration; }
    public int largePoolMaxIdleConnections()                 { return largePoolMaxIdleConnections; }
    public Duration largePoolKeepAliveDuration()             { return largePoolKeepAliveDuration; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<Endpoint> endpoints = List.of();
        private String accessKey;
        private String secretKey;
        private int maxRetries = 3;
        private Duration retryBaseDelay = Duration.ofMillis(200);
        private Duration healthCheckInterval = Duration.ofSeconds(10);
        private int circuitBreakerFailureThreshold = 3;
        private int circuitBreakerRecoveryThreshold = 2;
        private long bulkheadSizeThresholdBytes = 5L * 1024 * 1024; // 5 MB default
        private LoadBalancingStrategy loadBalancingStrategy = LoadBalancingStrategy.ROUND_ROBIN;
        private int smallPoolMaxIdleConnections = 20;
        private Duration smallPoolKeepAliveDuration = Duration.ofMinutes(5);
        private int largePoolMaxIdleConnections = 5;
        private Duration largePoolKeepAliveDuration = Duration.ofMinutes(10);

        public Builder endpoints(List<String> urls) {
            this.endpoints = urls.stream().map(Endpoint::new).toList();
            return this;
        }
        public Builder credentials(String accessKey, String secretKey) {
            this.accessKey = Objects.requireNonNull(accessKey);
            this.secretKey = Objects.requireNonNull(secretKey);
            return this;
        }
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder retryBaseDelay(Duration d) { this.retryBaseDelay = d; return this; }
        public Builder healthCheckInterval(Duration d) { this.healthCheckInterval = d; return this; }
        public Builder circuitBreakerFailureThreshold(int n) { this.circuitBreakerFailureThreshold = n; return this; }
        public Builder circuitBreakerRecoveryThreshold(int n) { this.circuitBreakerRecoveryThreshold = n; return this; }
        public Builder bulkheadSizeThresholdBytes(long bytes) { this.bulkheadSizeThresholdBytes = bytes; return this; }
        /**
         * How to pick the next healthy endpoint for stateless requests.
         * Multipart sessions pinned by {@code uploadId} ignore this setting.
         */
        public Builder loadBalancingStrategy(LoadBalancingStrategy strategy) {
            this.loadBalancingStrategy = Objects.requireNonNull(strategy, "loadBalancingStrategy");
            return this;
        }
        public Builder smallPoolMaxIdleConnections(int n) { this.smallPoolMaxIdleConnections = n; return this; }
        public Builder smallPoolKeepAliveDuration(Duration d) { this.smallPoolKeepAliveDuration = d; return this; }
        public Builder largePoolMaxIdleConnections(int n) { this.largePoolMaxIdleConnections = n; return this; }
        public Builder largePoolKeepAliveDuration(Duration d) { this.largePoolKeepAliveDuration = d; return this; }

        public HaMinioClientConfig build() {
            if (endpoints.isEmpty()) throw new IllegalStateException("At least one endpoint is required");
            Objects.requireNonNull(accessKey, "accessKey required");
            Objects.requireNonNull(secretKey, "secretKey required");
            return new HaMinioClientConfig(this);
        }
    }
}
