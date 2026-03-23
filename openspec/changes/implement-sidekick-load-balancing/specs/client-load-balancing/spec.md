# Client-Side Load Balancing and HA

## Context
The MinIO client needs to handle multiple MinIO endpoints simultaneously to achieve high availability and load distribution, similar to a Sidekick setup. This uses OkHttpClient interceptors and connection pools internally.

## ADDED Requirements

### Requirement: Active Endpoint Routing
The system MUST route stateless MinIO operations evenly across all healthy registered endpoints using a defined algorithm (e.g., Round-robin).

#### Scenario: Round-robin distribution
*   Given 3 active MinIO endpoints
*   When the client performs 3 sequential `statObject` requests
*   Then each endpoint MUST receive exactly 1 request

### Requirement: Idempotent & Safe Transparent Failover
The system MUST transparently retry requests on a different healthy endpoint if an operation fails due to network or 5xx errors, **provided `OkHttp.RequestBody.isOneShot()` returns `false`** (i.e., the request body can be replayed).

#### Scenario: Failover on a replayable (non-one-shot) request
*   Given 2 active MinIO endpoints (Node A and Node B)
*   When a `getObject` request is sent to Node A and Node A refuses the connection
*   Then the client MUST automatically retry the request on Node B
*   And the client MUST mark Node A as temporarily unhealthy

#### Scenario: No failover on one-shot (non-replayable) request bodies
*   Given 2 active MinIO endpoints
*   When a `putObject` request whose `RequestBody.isOneShot()` returns `true` fails mid-transfer
*   Then the system MUST NOT retry the request to avoid data corruption or stream exhaustion
*   But MUST throw the error back to the caller immediately

### Requirement: Retry Budget and Backoff
The system MUST limit the number of retry attempts and apply an exponential-backoff-with-jitter strategy to avoid amplifying load during degraded states.

#### Scenario: Retry budget is exhausted
*   Given all registered MinIO endpoints are unavailable
*   When the client attempts an operation with `maxRetries` exhausted
*   Then the system MUST stop retrying and throw the last observed exception to the caller

#### Scenario: Backoff prevents thundering herd
*   Given 1 endpoint recovers after a period of failure
*   When multiple clients retry simultaneously at the scheduled backoff intervals
*   Then retry delays MUST include randomized jitter so not all clients hit the endpoint at the exact same moment

### Requirement: Multipart Upload Session Affinity
The system MUST maintain session affinity (node-stickiness) for the full lifecycle of a multipart upload transaction (`CreateMultipartUpload` → `UploadPart` × N → `CompleteMultipartUpload`), because a MinIO `uploadId` is bound to the originating endpoint unless the cluster uses distributed erasure code.

#### Scenario: All UploadPart requests are routed to the same node as CreateMultipartUpload
*   Given an HA MinIO client and an in-progress multipart upload with `uploadId` = "abc" initiated on Node A
*   When the client uploads parts 1, 2, and 3
*   Then all UploadPart and CompleteMultipartUpload calls MUST be routed to Node A
*   And the load balancer MUST NOT redistribute these calls to other endpoints mid-session

#### Scenario: Node failure mid-multipart is propagated, not silently rerouted
*   Given an in-progress multipart upload whose originating node (Node A) crashes
*   When the client attempts to upload the next part
*   Then the system MUST throw an exception to the caller with a clear message indicating the multipart session is aborted
*   And the system MUST NOT attempt to replay `UploadPart` on a different endpoint

### Requirement: MinIO-Native Health Checking
The system MUST periodically probe endpoints using MinIO's native `/minio/health/ready` or `/minio/health/live` APIs to determine health status, with configurable interval and recovery thresholds to prevent flapping and achieve fast exclusion from rotation.

#### Scenario: MinIO readiness recovery is detected (flapping protection)
*   Given an endpoint previously marked as unhealthy
*   When the background health check receives HTTP 200 OK from `/minio/health/ready` for M consecutive checks (configurable, default M=2)
*   Then the system MUST mark the endpoint as healthy
*   And the load balancer MUST include it in future request routing

#### Scenario: Repeated failures trigger circuit-breaker open state
*   Given an endpoint that has failed N consecutive health checks (configurable, default N=3)
*   When the circuit breaker opens
*   Then the load balancer MUST immediately stop dispatching any requests to that endpoint
*   And health probes MUST continue in the background at `healthCheckInterval` (configurable, default 10s) to detect recovery
