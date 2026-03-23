package io.github.haminio.endpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the health state of all registered MinIO endpoints.
 * <p>
 * Uses a plain JDK {@link HttpClient} (NOT MinIO SDK) to probe
 * {@code /minio/health/ready} at configurable intervals.
 * Each endpoint has its own {@link DefaultCircuitBreaker}.
 */
public class DefaultEndpointManager implements EndpointManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultEndpointManager.class);

    private final List<Endpoint> allEndpoints;
    private final Map<Endpoint, DefaultCircuitBreaker> breakers;
    private final Duration healthCheckInterval;
    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    public DefaultEndpointManager(
            List<Endpoint> endpoints,
            int failureThreshold,
            int recoveryThreshold,
            Duration healthCheckInterval) {
        this.allEndpoints = List.copyOf(endpoints);
        this.healthCheckInterval = healthCheckInterval;
        this.breakers = new ConcurrentHashMap<>();
        for (Endpoint ep : endpoints) {
            breakers.put(ep, new DefaultCircuitBreaker(failureThreshold, recoveryThreshold));
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public List<Endpoint> healthyEndpoints() {
        return allEndpoints.stream()
                .filter(ep -> breakers.get(ep).allowRequest())
                .toList();
    }

    @Override
    public void markFailed(Endpoint endpoint) {
        DefaultCircuitBreaker cb = breakers.get(endpoint);
        if (cb != null) {
            cb.recordFailure();
            log.warn("Endpoint marked as failed (passive): {}", endpoint);
        }
    }

    @Override
    public void markRecovered(Endpoint endpoint) {
        DefaultCircuitBreaker cb = breakers.get(endpoint);
        if (cb != null) {
            cb.recordSuccess();
        }
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "haminio-health-checker");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = healthCheckInterval.toMillis();
        scheduler.scheduleAtFixedRate(this::probeAll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Health checker started; interval={}ms, endpoints={}", intervalMs, allEndpoints);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ── Internal probing ──────────────────────────────────────────────────

    private void probeAll() {
        for (Endpoint ep : allEndpoints) {
            probe(ep);
        }
    }

    void probe(Endpoint ep) {
        DefaultCircuitBreaker cb = breakers.get(ep);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ep.healthCheckUrl()))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 200) {
                if (cb.state() == CircuitBreaker.State.OPEN) {
                    cb.attemptReset(); // OPEN → HALF_OPEN
                    log.info("Endpoint probe OK, transitioning to HALF_OPEN: {}", ep);
                } else {
                    cb.recordSuccess();
                }
            } else {
                cb.recordFailure();
                log.warn("Endpoint probe returned HTTP {} for {}", resp.statusCode(), ep);
            }
        } catch (IOException | InterruptedException e) {
            cb.recordFailure();
            log.warn("Endpoint probe failed for {}: {}", ep, e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Exposed for testing only. */
    public CircuitBreaker.State circuitState(Endpoint ep) {
        return breakers.get(ep).state();
    }
}
