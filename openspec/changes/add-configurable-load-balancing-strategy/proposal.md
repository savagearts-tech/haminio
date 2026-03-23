---
title: Add configurable client-side load balancing strategy
status: implemented
author: agent
created: 2026-03-22
---

# Add configurable client-side load balancing strategy

## Why

The HA MinIO client already routes stateless traffic across healthy endpoints via `LoadBalancer` and `LoadBalancingInterceptor`, but `HaMinioClientFactory` always constructs `RoundRobinLoadBalancer` with no way for callers to choose another strategy. The broader change `implement-sidekick-load-balancing` already describes round-robin distribution and multipart affinity; this proposal narrows scope to **making the load balancing strategy an explicit, configurable capability** so operators can align routing with deployment needs without forking the factory.

## What Changes

- Specify that the client SHALL expose a **documented enumeration** of load balancing strategies (minimum: `ROUND_ROBIN` as default, `RANDOM` as an optional alternative for stateless requests).
- Specify that **multipart upload session affinity** continues to override strategy for pinned `uploadId` traffic.
- Implementation tasks (apply stage): extend `HaMinioClientConfig`, add a `RandomLoadBalancer` (or equivalent) implementing `LoadBalancer`, wire selection in `HaMinioClientFactory`, and add unit tests for distribution / stickiness precedence.

## Impact

- **Affected specs (delta):** `client-load-balancing` (new requirements under this change).
- **Affected code (apply stage):** `HaMinioClientConfig`, `HaMinioClientFactory`, new balancer implementation(s), tests.
- **Related active change:** `implement-sidekick-load-balancing` (HA, failover, bulkhead, observability). This change is **orthogonal**: it does not replace that proposal; teams should merge or sequence tasks to avoid duplicate work.

## Non-Goals

- Least-connections or weighted routing (requires connection-level metrics not specified here).
- Changing failover, health check, or circuit breaker behavior.
