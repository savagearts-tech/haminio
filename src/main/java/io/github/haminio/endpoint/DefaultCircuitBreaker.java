package io.github.haminio.endpoint;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Three-state circuit breaker per endpoint.
 * <p>
 * CLOSED  → normal operation, allows all requests.
 * OPEN    → endpoint is tripped; requests are blocked immediately.
 * HALF_OPEN → probing recovery; allows limited requests to check endpoint health.
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

    private final int failureThreshold;
    private final int recoveryThreshold;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);

    public DefaultCircuitBreaker(int failureThreshold, int recoveryThreshold) {
        this.failureThreshold = failureThreshold;
        this.recoveryThreshold = recoveryThreshold;
    }

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public boolean allowRequest() {
        State s = state.get();
        return s == State.CLOSED || s == State.HALF_OPEN;
    }

    @Override
    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        State s = state.get();
        if (s == State.HALF_OPEN) {
            int successes = consecutiveSuccesses.incrementAndGet();
            if (successes >= recoveryThreshold) {
                consecutiveSuccesses.set(0);
                state.set(State.CLOSED);
            }
        } else {
            consecutiveSuccesses.set(0);
        }
    }

    @Override
    public synchronized void recordFailure() {
        consecutiveSuccesses.set(0);
        State s = state.get();
        if (s == State.CLOSED || s == State.HALF_OPEN) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold) {
                consecutiveFailures.set(0);
                state.set(State.OPEN);
            }
        }
    }

    /**
     * Called by EndpointManager when a health-check probe succeeds
     * and the circuit is OPEN — transitions to HALF_OPEN to start probing.
     */
    public synchronized void attemptReset() {
        if (state.get() == State.OPEN) {
            consecutiveFailures.set(0);
            consecutiveSuccesses.set(0);
            state.set(State.HALF_OPEN);
        }
    }
}
