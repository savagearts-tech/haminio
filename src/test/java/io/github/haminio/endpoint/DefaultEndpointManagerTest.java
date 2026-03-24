package io.github.haminio.endpoint;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultEndpointManager}.
 *
 * <h2>Regression: immediate health probe on start()</h2>
 * Previously, {@code ScheduledExecutorService.scheduleAtFixedRate} was called
 * with {@code initialDelay = healthCheckInterval}, meaning the first probe
 * would not run until the full interval had elapsed. Requests dispatched
 * immediately after {@link DefaultEndpointManager#start()} would therefore
 * operate on stale (default-CLOSED) circuit-breaker state even when the
 * endpoint was actually down.
 * <p>
 * Fix: {@code initialDelay = 0} — the first probe runs as soon as
 * {@code start()} schedules the task, well before the first interval elapses.
 */
class DefaultEndpointManagerTest {

    private MockWebServer healthyServer;
    private MockWebServer unhealthyServer;

    @BeforeEach
    void setUp() throws IOException {
        healthyServer   = new MockWebServer();
        unhealthyServer = new MockWebServer();
        healthyServer.start();
        unhealthyServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (healthyServer != null) {
                healthyServer.shutdown();
            }
        } finally {
            if (unhealthyServer != null) {
                unhealthyServer.shutdown();
            }
        }
    }

    // ── Basic state ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("healthyEndpoints() returns all endpoints before any probe (circuit CLOSED by default)")
    void allEndpointsHealthyBeforeFirstProbe() {
        Endpoint ep = endpoint(healthyServer);
        DefaultEndpointManager mgr = manager(List.of(ep), 3, 2, Duration.ofMinutes(10));

        // No start() called — circuit breakers default to CLOSED → all reported healthy
        assertEquals(1, mgr.healthyEndpoints().size(),
                "All endpoints must be in healthy list before any probe");
    }

    // ── Regression: initialDelay=0 ────────────────────────────────────────────

    @Test
    @DisplayName("Regression: first health probe runs immediately after start() (initialDelay=0)")
    void firstProbeRunsImmediatelyAfterStart() throws Exception {
        // A single successful probe response is enough to confirm the request arrived
        healthyServer.enqueue(new MockResponse().setResponseCode(200));

        Endpoint ep = endpoint(healthyServer);
        DefaultEndpointManager mgr = manager(List.of(ep), 3, 2, Duration.ofSeconds(60));

        mgr.start();
        // Allow the scheduler thread a short window to complete the probe.
        // The interval is 60 s, so any request received here proves initialDelay=0.
        Thread.sleep(300);

        long probeCount = healthyServer.getRequestCount();
        assertTrue(probeCount >= 1,
                "Health probe MUST run immediately after start() (initialDelay=0), "
                        + "but /minio/health/ready request count was: " + probeCount);

        mgr.stop();
    }

    // ── Probe outcomes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Endpoint is marked unhealthy when health probe returns non-200")
    void unhealthyProbeTripsCircuitBreaker() throws Exception {
        // failureThreshold=1 → one failing probe opens the circuit
        for (int i = 0; i < 3; i++) {
            unhealthyServer.enqueue(new MockResponse().setResponseCode(503));
        }

        Endpoint ep = endpoint(unhealthyServer);
        DefaultEndpointManager mgr = manager(List.of(ep), 1, 2, Duration.ofSeconds(60));

        mgr.start();
        Thread.sleep(300);

        assertEquals(CircuitBreaker.State.OPEN, mgr.circuitState(ep),
                "Circuit breaker must be OPEN after failing health probe");
        assertTrue(mgr.healthyEndpoints().isEmpty(),
                "healthyEndpoints() must be empty when only endpoint is OPEN");

        mgr.stop();
    }

    @Test
    @DisplayName("Endpoint stays healthy when probe returns 200")
    void healthyProbeKeepsBreakerClosed() throws Exception {
        healthyServer.enqueue(new MockResponse().setResponseCode(200));
        healthyServer.enqueue(new MockResponse().setResponseCode(200));

        Endpoint ep = endpoint(healthyServer);
        DefaultEndpointManager mgr = manager(List.of(ep), 3, 2, Duration.ofSeconds(60));

        mgr.start();
        Thread.sleep(300);

        assertEquals(CircuitBreaker.State.CLOSED, mgr.circuitState(ep),
                "Circuit breaker must remain CLOSED after successful probe");
        assertFalse(mgr.healthyEndpoints().isEmpty(),
                "healthyEndpoints() must include the endpoint when probe succeeds");

        mgr.stop();
    }

    // ── Passive failure / recovery ────────────────────────────────────────────

    @Test
    @DisplayName("markFailed() trips circuit breaker when failure threshold is reached")
    void passiveFailureTripsCircuit() {
        Endpoint ep = endpoint(healthyServer);
        // failureThreshold=2 → two markFailed() calls open the circuit
        DefaultEndpointManager mgr = manager(List.of(ep), 2, 1, Duration.ofMinutes(10));

        mgr.markFailed(ep);
        assertEquals(CircuitBreaker.State.CLOSED, mgr.circuitState(ep),
                "Circuit must stay CLOSED after first passive failure (threshold not yet reached)");

        mgr.markFailed(ep);
        assertEquals(CircuitBreaker.State.OPEN, mgr.circuitState(ep),
                "Circuit must be OPEN after second passive failure (threshold=2 reached)");
    }

    @Test
    @DisplayName("markRecovered() advances circuit from HALF_OPEN to CLOSED")
    void passiveRecoveryClosesCircuit() throws Exception {
        // Drive endpoint into OPEN via two mark-failed calls (threshold=2)
        Endpoint ep = endpoint(healthyServer);
        DefaultEndpointManager mgr = manager(List.of(ep), 2, 1, Duration.ofMinutes(10));

        mgr.markFailed(ep);
        mgr.markFailed(ep);
        assertEquals(CircuitBreaker.State.OPEN, mgr.circuitState(ep));

        // Enqueue a probe response so probe() can attempt a reset to HALF_OPEN
        healthyServer.enqueue(new MockResponse().setResponseCode(200));
        mgr.probe(ep); // OPEN → HALF_OPEN (because probe returns 200)

        assertEquals(CircuitBreaker.State.HALF_OPEN, mgr.circuitState(ep),
                "Circuit must be HALF_OPEN after successful probe of an OPEN endpoint");

        // One successful request report closes it (recoveryThreshold=1)
        mgr.markRecovered(ep);
        assertEquals(CircuitBreaker.State.CLOSED, mgr.circuitState(ep),
                "Circuit must be CLOSED after markRecovered() meets the recovery threshold");
    }

    @Test
    @DisplayName("Two endpoints: unhealthy one removed from healthyEndpoints list")
    void multiEndpoint_unhealthyRemovedFromList() throws Exception {
        // failureThreshold=1 for fast tripping
        for (int i = 0; i < 3; i++) {
            unhealthyServer.enqueue(new MockResponse().setResponseCode(500));
        }
        healthyServer.enqueue(new MockResponse().setResponseCode(200));
        healthyServer.enqueue(new MockResponse().setResponseCode(200));

        Endpoint good = endpoint(healthyServer);
        Endpoint bad  = endpoint(unhealthyServer);
        DefaultEndpointManager mgr = manager(List.of(good, bad), 1, 2, Duration.ofSeconds(60));

        mgr.start();
        Thread.sleep(400);

        List<Endpoint> healthy = mgr.healthyEndpoints();
        assertTrue(healthy.contains(good), "Healthy endpoint must be in the list");
        assertFalse(healthy.contains(bad),  "Unhealthy endpoint must NOT be in the list");

        mgr.stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Endpoint endpoint(MockWebServer server) {
        return new Endpoint(server.url("/").toString().replaceAll("/$", ""));
    }

    private static DefaultEndpointManager manager(
            List<Endpoint> endpoints, int failThreshold, int recoveryThreshold,
            Duration interval) {
        return new DefaultEndpointManager(endpoints, failThreshold, recoveryThreshold, interval);
    }
}
