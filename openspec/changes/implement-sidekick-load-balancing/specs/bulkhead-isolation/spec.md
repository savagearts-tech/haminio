# Bulkhead Isolation

## Context
A single connection pool handling both massive multipart uploads and tiny API operations (like `statObject` or small `putObject`) often suffers from pool exhaustion. Massive streams tie up connections for extended durations, leading to connection timeouts and latency spikes for concurrent small, fast requests. The system must segregate these requests using the Bulkhead Pattern.

## ADDED Requirements

### Requirement: Size-Based Pool Segregation
The client MUST maintain separate internal connection pools (implemented via distinct `OkHttpClient` instances) optimized for the payload size and operation type. The size threshold and pool sizes MUST be configurable via `HaMinioClientConfig`.

#### Scenario: Large object upload isolation
*   Given a MinIO client configured with a Bulkhead payload threshold (e.g., 5MB)
*   When the user initiates a massive 50GB file upload
*   Then the client MUST dispatch the request to the 'LARGE' connection pool
*   And concurrent `statObject` or small `< 5MB` requests MUST be routed to the 'SMALL' connection pool
*   And the 'SMALL' requests MUST NOT be delayed or blocked by the large upload's connection utilization

### Requirement: Unknown-Size Stream Conservative Routing
When the payload size of an upload request cannot be determined at request time (i.e., `Content-Length` is unknown or -1), the system MUST conservatively route the request to the 'LARGE' connection pool to avoid exhausting the 'SMALL' pool with potentially large streams.

#### Scenario: Stream with unknown length is routed to LARGE pool
*   Given a MinIO client with Bulkhead Isolation enabled
*   When the user calls `putObject` with an `InputStream` whose total length is unknown (Content-Length = -1)
*   Then the client MUST route the request to the 'LARGE' connection pool
*   And the 'SMALL' connection pool MUST remain unaffected
