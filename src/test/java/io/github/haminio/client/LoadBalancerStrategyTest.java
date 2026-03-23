package io.github.haminio.client;

import io.github.haminio.endpoint.Endpoint;
import io.github.haminio.endpoint.EndpointManager;
import io.github.haminio.interceptor.LoadBalancingInterceptor;
import io.github.haminio.interceptor.MultipartSessionRegistry;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalancerStrategyTest {

    private static final class FixedHealthy implements EndpointManager {
        private final List<Endpoint> healthy;

        FixedHealthy(List<Endpoint> healthy) {
            this.healthy = List.copyOf(healthy);
        }

        @Override
        public List<Endpoint> healthyEndpoints() {
            return healthy;
        }

        @Override
        public void markFailed(Endpoint endpoint) {}

        @Override
        public void markRecovered(Endpoint endpoint) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }

    @Test
    void roundRobin_cycles_three_endpoints() {
        Endpoint a = new Endpoint("http://a:9000");
        Endpoint b = new Endpoint("http://b:9000");
        Endpoint c = new Endpoint("http://c:9000");
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(new FixedHealthy(List.of(a, b, c)));

        assertEquals(a, lb.next());
        assertEquals(b, lb.next());
        assertEquals(c, lb.next());
        assertEquals(a, lb.next());
    }

    @Test
    void roundRobin_excludes_given_endpoints() {
        Endpoint a = new Endpoint("http://a:9000");
        Endpoint b = new Endpoint("http://b:9000");
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(new FixedHealthy(List.of(a, b)));

        assertEquals(b, lb.next(List.of(a)));
        assertEquals(a, lb.next(List.of(b)));
    }

    @Test
    void random_selects_only_healthy_and_eventually_varies() {
        Endpoint a = new Endpoint("http://a:9000");
        Endpoint b = new Endpoint("http://b:9000");
        Endpoint c = new Endpoint("http://c:9000");
        RandomLoadBalancer lb = new RandomLoadBalancer(new FixedHealthy(List.of(a, b, c)));

        Set<Endpoint> seen = new HashSet<>();
        for (int i = 0; i < 8000; i++) {
            Endpoint e = lb.next();
            assertNotNull(e);
            assertTrue(List.of(a, b, c).contains(e));
            seen.add(e);
        }
        assertTrue(seen.size() >= 2, "expected random to hit at least 2 endpoints over many trials");
    }

    @Test
    void config_default_strategy_is_round_robin() {
        HaMinioClientConfig cfg = HaMinioClientConfig.builder()
                .endpoints(List.of("http://x:9000"))
                .credentials("k", "s")
                .build();
        assertEquals(LoadBalancingStrategy.ROUND_ROBIN, cfg.loadBalancingStrategy());
    }

    @Test
    void config_rejects_null_strategy() {
        assertThrows(NullPointerException.class, () ->
                HaMinioClientConfig.builder()
                        .endpoints(List.of("http://x:9000"))
                        .credentials("k", "s")
                        .loadBalancingStrategy(null));
    }

    @Test
    void multipart_pinned_endpoint_used_even_with_random_balancer() throws IOException {
        try (MockWebServer serverA = new MockWebServer();
             MockWebServer serverB = new MockWebServer()) {
            serverA.start();
            serverB.start();

            Endpoint epA = new Endpoint(serverA.url("/").toString().replaceAll("/$", ""));
            Endpoint epB = new Endpoint(serverB.url("/").toString().replaceAll("/$", ""));
            FixedHealthy em = new FixedHealthy(List.of(epA, epB));
            RandomLoadBalancer lb = new RandomLoadBalancer(em);
            MultipartSessionRegistry reg = new MultipartSessionRegistry();
            reg.register("sticky-upload", epA);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(new LoadBalancingInterceptor(lb, reg))
                    .build();

            HttpUrl url = HttpUrl.parse("http://placeholder:1/bucket/obj")
                    .newBuilder()
                    .addQueryParameter("uploadId", "sticky-upload")
                    .addQueryParameter("partNumber", "1")
                    .build();
            Request req = new Request.Builder().url(url).get().build();

            for (int i = 0; i < 40; i++) {
                serverA.enqueue(new MockResponse().setBody("a"));
            }

            for (int i = 0; i < 40; i++) {
                try (Response resp = client.newCall(req).execute()) {
                    assertEquals(200, resp.code());
                }
            }

            assertEquals(40, serverA.getRequestCount());
            assertEquals(0, serverB.getRequestCount());
        }
    }
}
