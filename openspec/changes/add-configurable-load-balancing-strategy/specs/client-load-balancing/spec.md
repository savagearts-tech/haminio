# Client-side load balancing — strategy configuration

## Context

Stateful MinIO operations such as multipart uploads require session affinity. Stateless operations benefit from distributing load across healthy endpoints. The library already supports a `LoadBalancer` abstraction; this delta adds **explicit, configurable** routing strategies for stateless traffic.

## ADDED Requirements

### Requirement: Configurable load balancing strategy

The system SHALL allow the caller to select a load balancing strategy from a documented enumeration when building the HA MinIO client configuration. The default strategy SHALL be round-robin.

#### Scenario: Default strategy is round-robin

- **WHEN** the client is built without setting a load balancing strategy on the configuration
- **THEN** the implementation SHALL use round-robin among healthy endpoints for stateless requests

#### Scenario: Random strategy among healthy endpoints

- **WHEN** the caller sets the load balancing strategy to random
- **THEN** each call to select the next endpoint for a stateless request SHALL choose uniformly at random from the set of healthy endpoints (excluding any endpoints excluded for failover retry)

### Requirement: Multipart affinity overrides strategy

Multipart upload session affinity SHALL take precedence over the configured load balancing strategy for requests that carry a registered `uploadId`.

#### Scenario: Sticky multipart ignores strategy rotation

- **WHEN** a multipart upload session is pinned to a specific endpoint for a given `uploadId`
- **THEN** subsequent `UploadPart`, `CompleteMultipartUpload`, and `AbortMultipartUpload` requests for that `uploadId` MUST be routed to the pinned endpoint
- **AND** the configured load balancing strategy MUST NOT change that routing
