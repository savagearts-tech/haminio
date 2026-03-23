---
title: Implement Sidekick-like Load Balancing and High Availability
status: proposed
author: agent
created: 2026-03-22
---

# Implement Sidekick-like Load Balancing and High Availability

## Context
The user wants to construct a highly available MinIO client that connects **directly** to multiple MinIO server nodes (no intermediate proxy layer such as Nginx or Sidekick). All load balancing, failover, health checking, and connection management MUST be handled purely within the client library itself, establishing TCP connections from the client JVM directly to each MinIO node.

## Goals
- Implement client-side load balancing across multiple MinIO endpoints.
- Provide transparent failover and automatic retry for transient errors.
- Support health checking of MinIO endpoints to avoid routing requests to down nodes.
- Leverage OkHttpClient's built-in connection pool capabilities rather than constructing a custom multi-client connection pool.
- Implement Bulkhead Isolation using OkHttpClient's `ConnectionPool` to segregate large and small request workloads.
- Provide comprehensive observability (Metrics, Tracing, and Logging) natively via OkHttp interceptors to monitor endpoint health, request latency, and failover events.

## Architectural Constraints
- **No custom connection pool**: The library MUST NOT implement any custom connection pool. All TCP connection lifecycle management MUST be fully delegated to `OkHttpClient`'s native `ConnectionPool`.
- **Maximize existing capability reuse**: Before introducing any custom component, the implementation MUST verify that the capability does not already exist in the MinIO Java SDK or OkHttp API. Custom code is only permitted where no equivalent built-in mechanism exists.
- **Use OkHttp `EventListener` for I/O metrics**: Byte counting and connection timing MUST be collected via `OkHttpClient.eventListenerFactory(...)`, not custom Interceptors — this avoids re-implementing what OkHttp already computes natively.
- **Expose `ConnectionPool` state via direct API**: Pool gauges (idle/total connection counts) MUST be read directly from `OkHttpClient.connectionPool()` and registered as Micrometer gauges, requiring no Interceptor or reflection.

## Non-Goals
- This library does NOT route requests through any proxy layer (Nginx, HAProxy, MinIO Sidekick, or similar). All requests travel directly from the client to MinIO nodes over TCP.
- Client-side load balancing and HA will **not** attempt to cover `Presigned URL` generation, as they are intrinsically tied to the exact Host resolved at generation time.
- This library does NOT manage MinIO server-side clustering or erasure-code topology; it treats each registered endpoint as a peer node.
- The library MUST NOT wrap, re-implement, or decorate `OkHttpClient`'s `ConnectionPool`; it must use it directly as provided by the OkHttp API.
