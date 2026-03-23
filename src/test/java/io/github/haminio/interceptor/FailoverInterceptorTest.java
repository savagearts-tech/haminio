package io.github.haminio.interceptor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import io.github.haminio.endpoint.DefaultEndpointManager;
import io.github.haminio.endpoint.Endpoint;
import io.github.haminio.client.RoundRobinLoadBalancer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FailoverInterceptor using OkHttp MockWebServer.
 */
class FailoverInterceptorTest {

    private MockWebServer serverA;
    private MockWebServer serverB;
    private OkHttpClient client;
    private DefaultEndpointManager endpointManager;

    @BeforeEach
    void setUp() throws IOException {
        serverA = new MockWebServer();
        serverB = new MockWebServer();
        serverA.start();
        serverB.start();

        List<Endpoint> endpoints = List.of(
                new Endpoint(serverA.url("/").toString().replaceAll("/$", "")),
                new Endpoint(serverB.url("/").toString().replaceAll("/$", ""))
        );

        endpointManager = new DefaultEndpointManager(endpoints, 1, 1, Duration.ofMinutes(1));
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(endpointManager);
        MultipartSessionRegistry registry = new MultipartSessionRegistry();

        client = new OkHttpClient.Builder()
                .addInterceptor(new LoadBalancingInterceptor(lb, registry))
                .addInterceptor(new FailoverInterceptor(endpointManager, 2, Duration.ofMillis(10)))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        serverA.shutdown();
        serverB.shutdown();
    }

    @Test
    void failsoverToServerBWhenServerAReturns500() throws IOException {
        serverA.enqueue(new MockResponse().setResponseCode(500));
        serverB.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        Request req = new Request.Builder()
                .url(serverA.url("/test"))
                .get()
                .build();

        try (Response response = client.newCall(req).execute()) {
            assertEquals(200, response.code());
        }
    }

    @Test
    void oneShotBodyIsNotRetried() {
        serverA.enqueue(new MockResponse().setResponseCode(500));

        // One-shot body simulating a non-replayable InputStream
        RequestBody oneShotBody = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/octet-stream"); }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(okio.BufferedSink sink) throws IOException {
                sink.writeUtf8("data");
            }
        };

        Request req = new Request.Builder()
                .url(serverA.url("/upload"))
                .put(oneShotBody)
                .build();

        // Expect the 500 to be returned without retry (serverB gets no request enqueued)
        try (Response response = client.newCall(req).execute()) {
            assertEquals(500, response.code());
            assertEquals(0, serverB.getRequestCount(),
                    "One-shot body MUST NOT be retried on serverB");
        } catch (IOException e) {
            // A connection failure is also acceptable — what matters is no retry on serverB
            assertEquals(0, serverB.getRequestCount());
        }
    }
}
