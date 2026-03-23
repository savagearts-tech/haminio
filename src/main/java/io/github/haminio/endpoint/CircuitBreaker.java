package io.github.haminio.endpoint;

/**
 * Circuit breaker state machine for a single MinIO endpoint.
 * <p>
 * States: CLOSED (healthy) → OPEN (tripped) → HALF_OPEN (probing recovery).
 */
public interface CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Returns the current state of the circuit breaker. */
    State state();

    /** Returns true if requests should be allowed through (CLOSED or HALF_OPEN). */
    boolean allowRequest();

    /** Records a successful call; may transition HALF_OPEN → CLOSED. */
    void recordSuccess();

    /** Records a failed call; may transition CLOSED → OPEN. */
    void recordFailure();
}
