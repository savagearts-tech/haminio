package io.github.haminio.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp {@link EventListener} implementation that collects per-call metrics
 * using Micrometer, leveraging OkHttp's native callback hooks.
 * <p>
 * Per architectural constraint C3: byte counting and connection timing
 * MUST use OkHttp's EventListener API, NOT custom Interceptors.
 * <p>
 * Registered metrics:
 * - {@code minio.client.request.duration} (Timer) tagged with {endpoint, method}
 * - {@code minio.client.request.failures} (Counter) tagged with {endpoint, method}
 * - {@code minio.client.bytes.uploaded} (Counter) tagged with {endpoint}
 * - {@code minio.client.bytes.downloaded} (Counter) tagged with {endpoint}
 * - {@code minio.client.connect.failures} (Counter) tagged with {endpoint}
 */
public class MinioEventListener extends EventListener {

    private final MeterRegistry registry;
    private final String endpoint;

    private long callStartNanos;

    public MinioEventListener(MeterRegistry registry, String endpoint) {
        this.registry = registry;
        this.endpoint = endpoint;
    }

    @Override
    public void callStart(@NotNull Call call) {
        callStartNanos = System.nanoTime();
    }

    @Override
    public void callEnd(@NotNull Call call) {
        long durationNanos = System.nanoTime() - callStartNanos;
        Timer.builder("minio.client.request.duration")
                .tag("endpoint", endpoint)
                .tag("method", call.request().method())
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void callFailed(@NotNull Call call, @NotNull IOException ioe) {
        Counter.builder("minio.client.request.failures")
                .tag("endpoint", endpoint)
                .tag("method", call.request().method())
                .register(registry)
                .increment();
    }

    @Override
    public void requestBodyEnd(@NotNull Call call, long byteCount) {
        Counter.builder("minio.client.bytes.uploaded")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment(byteCount);
    }

    @Override
    public void responseBodyEnd(@NotNull Call call, long byteCount) {
        Counter.builder("minio.client.bytes.downloaded")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment(byteCount);
    }

    @Override
    public void connectFailed(@NotNull Call call,
                              @NotNull InetSocketAddress inetSocketAddress,
                              @NotNull Proxy proxy,
                              @Nullable okhttp3.Protocol protocol,
                              @NotNull IOException ioe) {
        Counter.builder("minio.client.connect.failures")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment();
    }

    /** Factory so each OkHttp call gets its own listener instance (thread-safe). */
    public static Factory factory(MeterRegistry registry, String endpoint) {
        return call -> new MinioEventListener(registry, endpoint);
    }
}
