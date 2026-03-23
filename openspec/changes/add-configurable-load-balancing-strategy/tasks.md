# Tasks

## 1. Specification and validation

- [x] 1.1 Confirm delta in `specs/client-load-balancing/spec.md` matches intended scope with stakeholders.
- [x] 1.2 Run `openspec validate add-configurable-load-balancing-strategy --strict --no-interactive` after edits.

## 2. Configuration API

- [x] 2.1 Introduce `LoadBalancingStrategy` enum (`ROUND_ROBIN`, `RANDOM`) in the client package.
- [x] 2.2 Add `loadBalancingStrategy` to `HaMinioClientConfig` with default `ROUND_ROBIN` on `Builder`.
- [x] 2.3 Add builder method `loadBalancingStrategy(LoadBalancingStrategy)` with null rejection if applicable.

## 3. Implementations

- [x] 3.1 Implement `RandomLoadBalancer` (or package-private class) implementing `LoadBalancer`, using `EndpointManager` / healthy endpoint list consistent with `RoundRobinLoadBalancer`.
- [x] 3.2 Add a small factory or switch in `HaMinioClientFactory` to construct the correct `LoadBalancer` from config.

## 4. Validation (tests)

- [x] 4.1 Unit test: over many iterations, `RANDOM` selects only among healthy endpoints and eventually touches more than one when multiple are healthy (statistical smoke).
- [x] 4.2 Unit test: `ROUND_ROBIN` still distributes evenly across three healthy endpoints for sequential `next()` calls (align with existing spec intent).
- [x] 4.3 Test or document that multipart affinity is unchanged (existing interceptor tests may already cover stickiness; extend if strategy could regress routing for pinned sessions).

## 5. Coordination

- [x] 5.1 Reconcile task overlap with `implement-sidekick-load-balancing` (same files: factory, config) before merge to main.
