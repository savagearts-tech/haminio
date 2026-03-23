package io.github.haminio.client;

/**
 * Selects how stateless requests are distributed across healthy MinIO endpoints.
 * Multipart upload affinity overrides the strategy for pinned {@code uploadId} traffic.
 */
public enum LoadBalancingStrategy {

    /** Cycle through healthy endpoints in order. */
    ROUND_ROBIN,

    /** Choose uniformly at random among healthy endpoints (per selection). */
    RANDOM
}
