package io.github.haminio.integration;

import io.github.haminio.client.HaMinioClient;
import io.github.haminio.client.HaMinioClientConfig;
import io.github.haminio.client.HaMinioClientFactory;
import io.github.haminio.client.LoadBalancingStrategy;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test with <strong>two</strong> independent fake MinIO nodes.
 * Objects are not replicated automatically; tests explicitly pre-populate
 * both stores where needed — matching the original twin-container topology.
 * <p>
 * Uses {@link MinioMockServer} instead of Docker/Testcontainers.
 */
class HaMinioClientTwinContainersIT {

    private static final String ACCESS = "minioaccess";
    private static final String SECRET = "miniosecret";

    private MinioMockServer serverA;
    private MinioMockServer serverB;
    private String urlA;
    private String urlB;
    private String bucket;
    private HaMinioClient haClient;

    @BeforeEach
    void setUp() throws Exception {
        serverA = new MinioMockServer();
        serverB = new MinioMockServer();

        urlA = serverA.url();
        urlB = serverB.url();
        bucket = "ha-twin-" + UUID.randomUUID();

        // Bootstrap: create the bucket on both nodes via the putRaw package-private API
        serverA.putRaw(bucket, ".keep", new byte[0]);
        serverA.removeRaw(bucket, ".keep");
        serverB.putRaw(bucket, ".keep", new byte[0]);
        serverB.removeRaw(bucket, ".keep");

        HaMinioClientConfig config = HaMinioClientConfig.builder()
                .endpoints(List.of(urlA, urlB))
                .credentials(ACCESS, SECRET)
                .healthCheckInterval(Duration.ofMillis(500))
                .retryBaseDelay(Duration.ofMillis(50))
                .loadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)
                .build();

        haClient = HaMinioClientFactory.create(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (haClient != null) {
            haClient.close();
        }
        if (serverA != null) serverA.close();
        if (serverB != null) serverB.close();
    }

    @Test
    void replicatedObject_visibleFromHaClientAcrossBothNodes() throws Exception {
        String key     = "shared-" + UUID.randomUUID();
        byte[] payload = "twin-node-payload".getBytes(StandardCharsets.UTF_8);

        // Pre-populate both nodes (simulating replication)
        putObjectDirect(serverA, bucket, key, payload);
        putObjectDirect(serverB, bucket, key, payload);

        for (int i = 0; i < 8; i++) {
            assertTrue(haClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build()).size() > 0);
        }

        try (GetObjectResponse resp = haClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            assertEquals("twin-node-payload", new String(resp.readAllBytes(), StandardCharsets.UTF_8));
        }

        removeObjectDirect(serverA, bucket, key);
        removeObjectDirect(serverB, bucket, key);
    }

    @Test
    void haPut_thenReplicate_thenGetSucceedsOnEitherNode() throws Exception {
        String key     = "ha-put-" + UUID.randomUUID();
        byte[] payload = "written-by-ha".getBytes(StandardCharsets.UTF_8);

        haClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(new ByteArrayInputStream(payload), payload.length, -1)
                .build());

        // Replicate to the node that is missing the object
        replicateToMissingNode(bucket, key);

        try (GetObjectResponse resp = haClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            assertEquals("written-by-ha", new String(resp.readAllBytes(), StandardCharsets.UTF_8));
        }

        removeObjectDirect(serverA, bucket, key);
        removeObjectDirect(serverB, bucket, key);
    }

    // ── Direct store helpers ──────────────────────────────────────────────────

    private void replicateToMissingNode(String b, String key) {
        boolean onA = serverA.existsRaw(b, key);
        boolean onB = serverB.existsRaw(b, key);
        if (onA && !onB) {
            serverB.putRaw(b, key, serverA.getRaw(b, key));
        } else if (onB && !onA) {
            serverA.putRaw(b, key, serverB.getRaw(b, key));
        }
    }

    private static void putObjectDirect(MinioMockServer server, String bucket, String key, byte[] payload) {
        server.putRaw(bucket, key, payload);
    }

    private static void removeObjectDirect(MinioMockServer server, String bucket, String key) {
        server.removeRaw(bucket, key);
    }
}
