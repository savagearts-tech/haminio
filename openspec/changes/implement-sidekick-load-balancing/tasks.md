# Implementation Tasks

1.  **Project Scaffold and Core Interfaces**
    *   Create base interfaces for `LoadBalancer`, `EndpointManager`, and `CircuitBreaker`.
    *   Define `HaMinioClient` wrapper (composition, NOT inheritance) for basic MinIO operations.
    *   Define `HaMinioClientConfig` with fields: endpoint list, `maxRetries`, `healthCheckInterval`, `circuitBreakerFailureThreshold` (N), `circuitBreakerRecoveryThreshold` (M), bulkhead size threshold.
    *   Validation: Unit tests for class loading and interface implementations.

2.  **Endpoint Health Management with Circuit Breaker**
    *   Implement `EndpointManager` with background scheduler probing `/minio/health/ready` at `healthCheckInterval`.
    *   Implement Circuit Breaker: CLOSED → OPEN after N consecutive failures; OPEN → HALF-OPEN after recovery wait; HALF-OPEN → CLOSED after M consecutive successes (flapping protection).
    *   Validation: Unit tests simulating endpoint failures, recovery, and flapping scenarios.

3.  **Client-Side Load Balancing**
    *   Implement Round Robin routing strategy.
    *   Implement OkHttp Interceptor for dynamic endpoint routing (skipping OPEN circuit endpoints).
    *   Implement Multipart Upload Session Affinity: bind `uploadId` → originating endpoint in an internal registry, route all parts and Complete/Abort calls to the sticky endpoint.
    *   Validation: Tests verifying even distribution of mock requests; tests verifying all multipart parts hit the same node.

4.  **Failover and Retry Logic**
    *   Implement OkHttp Failover Interceptor: check `RequestBody.isOneShot()` before retry.
    *   Implement exponential backoff with jitter; respect `maxRetries` from `HaMinioClientConfig`.
    *   Validation: Integration tests using MockWebServer to inject connection refusal and 5xx errors; assert retry count and backoff timing.

5.  **Bulkhead Isolation**
    *   Use `HaMinioClientConfig` bulkhead threshold to categorize requests.
    *   Initialize and manage segregated LARGE and SMALL `OkHttpClient` instances with isolated `ConnectionPool` configurations.
    *   Implement Bulkhead Segregator: route LARGE (or unknown Content-Length == -1) requests to LARGE pool; all others to SMALL pool.
    *   Validation: Concurrency tests demonstrating that large uploads do not block concurrent small requests.

6.  **Observability Integration**
    *   Implement `ObservabilityInterceptor` for OkHttp:
        *   Record per-request latency histogram tagged with `{endpoint, operation, status}`.
        *   Increment success/failure/retry counters.
        *   Accumulate bytes-uploaded/downloaded per endpoint.
    *   Expose `OkHttpClient` connection pool state (active, idle, queued) for both LARGE and SMALL pools via Micrometer gauges.
    *   Expose endpoint health status gauge (0=unhealthy/open, 1=healthy/closed) for each registered endpoint.
    *   Validation: Unit tests asserting metrics are recorded correctly for successful, failed, and retried requests; verify pool gauges reflect connection utilization.

7.  **Integration Testing**
    *   Set up Testcontainers with MinIO container(s) for realistic multi-node integration tests.
    *   Use OkHttp MockWebServer for deterministic fault injection (connection refusal, partial response, 5xx).
    *   End-to-end test scenarios: upload/download under node failure, multipart upload with mid-session node failure, concurrent large+small request isolation.
