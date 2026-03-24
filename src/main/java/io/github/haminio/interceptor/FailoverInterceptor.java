package io.github.haminio.interceptor;

import io.github.haminio.endpoint.Endpoint;
import io.github.haminio.endpoint.EndpointManager;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * OkHttp Application Interceptor that transparently retries requests
 * on a different healthy endpoint after a network failure or 5xx response.
 * <p>
 * Contract:
 * - Only retries if {@code RequestBody.isOneShot()} returns {@code false}.
 *   One-shot bodies (non-replayable streams) are immediately propagated as failure.
 * - Applies exponential backoff with random jitter between cross-endpoint retries.
 * - Respects {@code maxRetries} budget; exhausting budget throws the last exception.
 * - On failure, passively notifies {@link EndpointManager} via {@link #endpointManager}.
 * <p>
 * Connection reuse across retries is handled entirely by OkHttp's ConnectionPool.
 */
public class FailoverInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(FailoverInterceptor.class);
    private static final Random RANDOM = new Random();

    private final EndpointManager endpointManager;
    private final int maxRetries;
    private final Duration baseDelay;

    public FailoverInterceptor(EndpointManager endpointManager, int maxRetries, Duration baseDelay) {
        this.endpointManager = Objects.requireNonNull(endpointManager);
        this.maxRetries = maxRetries;
        this.baseDelay = Objects.requireNonNull(baseDelay);
    }

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // Guard: one-shot bodies must never be retried
        if (request.body() != null && request.body().isOneShot()) {
            log.debug("Request body is one-shot; failover disabled for {}", request.url());
            return chain.proceed(request);
        }

        List<Endpoint> tried = new ArrayList<>();
        IOException lastException = null;
        Response lastResponse = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // Determine current target from the already-rewritten URL
            String currentHost = request.url().host();
            int currentPort   = request.url().port();
            Endpoint current = new Endpoint(request.url().scheme() + "://" + currentHost + ":" + currentPort);

            if (attempt > 0) {
                log.warn("Failover attempt {}/{} from {} due to: {}",
                        attempt, maxRetries, current, lastException != null ? lastException.getMessage() : "5xx");
            }

            try {
                Response response = chain.proceed(request);

                if (response.code() >= 500) {
                    // Server error — try another endpoint
                    response.close();
                    endpointManager.markFailed(current);
                    tried.add(current);
                    lastResponse = null;

                    Endpoint next = nextExcluding(tried);
                    if (next == null || attempt == maxRetries) break;
                    request = rewrite(request, next);
                    backoff(attempt);
                    continue;
                }

                // Successful response
                if (attempt > 0) endpointManager.markRecovered(current);
                return response;

            } catch (IOException ex) {
                endpointManager.markFailed(current);
                tried.add(current);
                lastException = ex;

                Endpoint next = nextExcluding(tried);
                if (next == null || attempt == maxRetries) break;
                request = rewrite(request, next);
                backoff(attempt);
            }
        }

        if (lastException != null) throw lastException;
        throw new IOException("All endpoints failed after " + maxRetries + " retries");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Endpoint nextExcluding(List<Endpoint> exclude) {
        List<Endpoint> candidates = new ArrayList<>(endpointManager.healthyEndpoints());
        candidates.removeAll(exclude);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private Request rewrite(Request original, Endpoint target) {
        HttpUrl originalUrl = original.url();
        HttpUrl newBase = HttpUrl.parse(target.url());
        // Must include port in Host header for non-standard ports (e.g. :9000).
        // AWS Signature V4 signs the Host header; omitting the port causes
        // SignatureDoesNotMatch when MinIO is not on the default HTTP/HTTPS port.
        String hostHeader = newBase.port() == 80 || newBase.port() == 443
                ? newBase.host()
                : newBase.host() + ":" + newBase.port();
        HttpUrl newUrl = newBase.newBuilder()
                .encodedPath(originalUrl.encodedPath())
                .encodedQuery(originalUrl.encodedQuery())
                .build();
        return original.newBuilder()
                .url(newUrl)
                .header("Host", hostHeader)
                .build();
    }

    /**
     * Exponential backoff with full jitter.
     * delay = random(0, min(cap, base * 2^attempt))
     */
    private void backoff(int attempt) {
        long capMs = 30_000L;
        long max = Math.min(capMs, baseDelay.toMillis() * (1L << attempt));
        long sleep = (long) (RANDOM.nextDouble() * max);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
