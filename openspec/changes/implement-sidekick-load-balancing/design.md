# Design: Sidekick-like Load Balancing and High Availability

## Architecture Overview
The highly available MinIO Client (`HaMinioClient`) establishes **direct TCP connections** from the client JVM to each registered MinIO server node — there is no intermediate proxy layer. It leverages a custom-configured `OkHttpClient` under the hood, utilizing its native `ConnectionPool` for connection reuse, and implements load balancing, failover, and observability through OkHttp Interceptors and the native `EventListener` API.

`HaMinioClient` uses the MinIO Java SDK (target: 8.5.x+) by injecting a custom `OkHttpClient` into each `MinioClient.builder()`. It does NOT extend `MinioClient`, and it does NOT re-implement any capability already available in `MinioClient` or `OkHttpClient`.

## Architectural Constraints

> **C1 — No custom connection pool.**
> All TCP connection lifecycle management (creation, reuse, eviction, idle timeout) is exclusively delegated to `OkHttpClient`'s `ConnectionPool`. The library only configures `ConnectionPool` parameters (maxIdleConnections, keepAliveDuration) via `HaMinioClientConfig`, and never wraps or re-implements the pool.

> **C2 — Maximize reuse of MinIO Java SDK and OkHttp built-in capabilities.**
> Before implementing any new component, verify whether the capability already exists in `MinioClient` or `OkHttp` APIs. Custom code is only permitted where no equivalent built-in mechanism exists — specifically: multi-endpoint URL routing, cross-endpoint failover logic, and endpoint health state tracking.

> **C3 — Use OkHttp `EventListener` for I/O metrics, not Interceptors.**
> Byte-level transfer accounting (`requestBodyEnd`, `responseBodyEnd`) and connection timing events (DNS resolution, connection acquisition, TLS handshake duration) MUST be collected via `OkHttpClient.eventListenerFactory(...)`, not custom Interceptors. This avoids duplicating what OkHttp already computes internally.

> **C4 — Expose `ConnectionPool` state via direct API, not instrumentation.**
> Connection pool gauges (idle count, total count) MUST be read directly from `OkHttpClient.connectionPool().idleConnectionCount()` and `connectionPool().connectionCount()` and registered as Micrometer gauges at startup. No Interceptor or reflection is needed.

### Core Components
1. **Endpoint Manager**: Maintains the registry of healthy/unhealthy MinIO node URLs. Drives active probing via MinIO's `/minio/health/ready` endpoint (HTTP GET, no MinIO SDK call needed). Implements circuit-breaker state (CLOSED / OPEN) per endpoint.
2. **OkHttp Load Balancing Interceptor** *(custom — not available in OkHttp or MinIO SDK)*: Rewrites the request `Host` + URL to the next healthy endpoint selected by the routing algorithm (Round Robin). Does not touch connection management.
3. **OkHttp Failover Interceptor** *(custom — not available in OkHttp or MinIO SDK)*: On network failure or 5xx response, checks `RequestBody.isOneShot()`. If replayable, retries on the next healthy endpoint. Respects `maxRetries` and applies exponential backoff with jitter.
4. **Bulkhead Segregator**: Evaluates `Content-Length` (or defaults to LARGE if unknown / -1) to dispatch to the appropriate `OkHttpClient` instance. The LARGE and SMALL clients each hold an independently configured `ConnectionPool` provided by OkHttp — no custom pooling code.
5. **Observability**: Three mechanisms, all OkHttp-native:
   - **`EventListener`** (per-call factory): Captures `callStart`, `callEnd`, `requestBodyEnd(byteCount)`, `responseBodyEnd(byteCount)`, `connectFailed`. Feeds Micrometer timers/counters.
   - **`ConnectionPool` gauges**: Polled directly from `OkHttpClient.connectionPool()` at runtime.
   - **Failover counter**: Incremented explicitly inside the Failover Interceptor on each cross-endpoint retry.

### Trade-offs
- **No proxy dependency**: The library owns endpoint discovery, health tracking, and failover. This increases client complexity but eliminates an intermediate single point of failure.
- **Dual OkHttpClient for Bulkhead**: Two `OkHttpClient` instances (with separate `ConnectionPool`s) prevent large-upload connection exhaustion from blocking small requests. Resource cost is bounded and configurable.
- **Client-managed multipart affinity**: Without a proxy layer, the client must explicitly bind `uploadId` → originating endpoint and enforce this in the interceptor chain.

### Integration Tests
Acceptance tests will use **Testcontainers (MinIO container)** to simulate multi-node behavior and **OkHttp MockWebServer** for deterministic fault injection (connection refusal, 5xx, slow responses).
