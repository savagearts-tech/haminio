package io.github.haminio.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCircuitBreakerTest {

    private DefaultCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        // failureThreshold=3, recoveryThreshold=2
        breaker = new DefaultCircuitBreaker(3, 2);
    }

    @Test
    void initiallyClosedAndAllowsRequests() {
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void opensAfterFailureThreshold() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        breaker.recordFailure(); // 3rd failure → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void halfOpenAfterAttemptReset() {
        // Trip the breaker
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        // Health check triggered reset
        breaker.attemptReset();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void closesAfterRecoveryThresholdFromHalfOpen() {
        breaker.recordFailure(); breaker.recordFailure(); breaker.recordFailure();
        breaker.attemptReset();

        breaker.recordSuccess(); // 1st success in HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        breaker.recordSuccess(); // 2nd success → CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void flappingProtection_halfOpenFailureReopens() {
        breaker.recordFailure(); breaker.recordFailure(); breaker.recordFailure();
        breaker.attemptReset();

        // Fails again in HALF_OPEN → back to OPEN
        breaker.recordFailure(); breaker.recordFailure(); breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void successResetsFailureCounterInClosed() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess(); // resets streak
        breaker.recordFailure();
        breaker.recordFailure();
        // Only 2 consecutive failures — should still be CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }
}
