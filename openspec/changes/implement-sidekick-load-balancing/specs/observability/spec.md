# Client-Side Observability

## Context
Operating a highly available client with complex internal behaviors (like load balancing and transparent failover) requires deep visibility. When requests failover behind the scenes, operators must be able to monitor the health of internal connection pools, downstream endpoint states, and operation latencies to prevent silent widespread degradations.

## ADDED Requirements

### Requirement: Operation Metrics Collection
The system MUST collect and expose metrics for every MinIO API operation, explicitly identifying the target endpoint, operation type, duration, and status.

#### Scenario: Metrics are recorded on successful requests
*   Given an observable HA MinIO client
*   When a `putObject` request successfully completes against Node A
*   Then a latency metric MUST be recorded tagged with Node A and operation `putObject`
*   And an operation success counter MUST be incremented

### Requirement: Failover and Health Observability
The system MUST expose the current health state of registered endpoints and explicitly track transparent retry events.

#### Scenario: Retry and failover events are monitored
*   Given an observable HA MinIO client
*   When a request fails on Node A (e.g., network timeout) and is transparently retried on Node B
*   Then a specific failover/retry counter MUST be incremented
*   And Node A's health gauge MUST reflect its currently unhealthy/degraded state

### Requirement: Connection Pool State Monitoring
The system MUST expose real-time metrics for each internal OkHttpClient connection pool (both LARGE and SMALL Bulkhead pools) to enable diagnosis of pool exhaustion, connection leaks, and request queuing.

#### Scenario: Connection pool metrics are observable under load
*   Given a high-concurrency environment with many concurrent MinIO operations
*   When the observable HA MinIO client is scraped (e.g., via Prometheus `/metrics` endpoint)
*   Then the response MUST include, for each pool (LARGE and SMALL):
    *   Active connection count (connections currently in use)
    *   Idle connection count (connections available for reuse)
    *   Queued request count (requests waiting for a connection)
*   And these values MUST reflect the real-time pool state at the moment of scraping

### Requirement: Throughput Metrics
The system MUST track cumulative data transfer volume (bytes uploaded and downloaded) per endpoint to enable throughput monitoring and bandwidth bottleneck identification.

#### Scenario: Throughput metrics are accumulated per endpoint
*   Given an observable HA MinIO client
*   When a 1GB `putObject` completes against Node B
*   Then the bytes-uploaded counter for Node B MUST be incremented by 1GB
*   And the throughput rate MUST be derivable from the metric time series
