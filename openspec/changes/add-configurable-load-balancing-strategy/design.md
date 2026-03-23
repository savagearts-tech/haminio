## Context

`LoadBalancer` is already the abstraction for picking the next healthy endpoint; `RoundRobinLoadBalancer` uses `EndpointManager` for the healthy set. Configuration today has no strategy field, so the factory cannot vary behavior.

## Goals / Non-Goals

- **Goals:** One configuration surface (`HaMinioClientConfig`) for strategy; default `ROUND_ROBIN`; at least one additional strategy (`RANDOM`) for stateless requests; thread-safe selection under concurrent calls; multipart stickiness unchanged (`LoadBalancingInterceptor` + `MultipartSessionRegistry`).
- **Non-Goals:** Pluggable third-party algorithms, dynamic runtime strategy switching, server-side weights.

## Decisions

- **Decision:** Add an enum (e.g. `LoadBalancingStrategy`) with `ROUND_ROBIN` and `RANDOM`, defaulting to `ROUND_ROBIN` on the config builder.
- **Rationale:** Keeps the API small and testable; matches common MinIO client deployment patterns without new dependencies.
- **Alternatives considered:** String-based strategy IDs (more error-prone); ServiceLoader plugins (overkill until multiple algorithms are required).

## Risks / Trade-offs

- **Random fairness:** Uniform random over "currently healthy" endpoints can skew under very low traffic; acceptable for stated non-goal of advanced policies.
- **Thread safety:** `RoundRobinLoadBalancer` uses atomic counters; `RANDOM` must use a thread-safe RNG (e.g. `ThreadLocalRandom`).

## Migration Plan

- Default remains round-robin; existing callers need no code changes.
- After apply: document new builder method in Javadoc only (no separate doc file unless project already maintains one).

## Open Questions

- Whether to expose strategy in metrics tags (defer to `implement-sidekick-load-balancing` observability tasks unless trivial).
