package io.github.haminio.integration;

import io.github.haminio.client.HaMinioClient;
import io.github.haminio.client.HaMinioClientConfig;
import io.github.haminio.client.HaMinioClientFactory;
import io.github.haminio.client.LoadBalancingStrategy;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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
 * End-to-end test with a <strong>single</strong> fake MinIO node exposed under two logical
 * endpoint URLs ({@code localhost} vs {@code 127.0.0.1}) so the HA client exercises load
 * balancing while S3 data stays consistent (same backing store).
 * <p>
 * Uses {@link MinioMockServer} instead of Docker/Testcontainers.
 */
class HaMinioClientDualEndpointAliasIT {

    private static final String ACCESS = "minioaccess";
    private static final String SECRET = "miniosecret";

    private MinioMockServer mockServer;
    private String bucket;
    private HaMinioClient haClient;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MinioMockServer();
        // url() returns "http://127.0.0.1:PORT" (MockWebServer binds to loopback).
        // endpointA uses localhost, endpointB uses 127.0.0.1 — two logical aliases for
        // the same server, matching the original dual-alias topology.
        String baseUrl  = mockServer.url(); // e.g. http://127.0.0.1:PORT
        int    port     = Integer.parseInt(baseUrl.replaceAll(".*:", ""));
        String endpointA = "http://localhost:" + port;
        String endpointB = "http://127.0.0.1:" + port;

        bucket = "ha-it-" + UUID.randomUUID();

        // Bootstrap: create the bucket via a direct MinIO client
        MinioClient bootstrap = MinioClient.builder()
                .endpoint(endpointA)
                .credentials(ACCESS, SECRET)
                .build();
        if (!bootstrap.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            bootstrap.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        HaMinioClientConfig config = HaMinioClientConfig.builder()
                .endpoints(List.of(endpointA, endpointB))
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
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void roundRobinStatAndGetAcrossLogicalEndpoints() throws Exception {
        String key     = "obj-" + UUID.randomUUID();
        byte[] payload = "e2e-dual-alias".getBytes(StandardCharsets.UTF_8);

        haClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(new ByteArrayInputStream(payload), payload.length, -1)
                .build());

        for (int i = 0; i < 6; i++) {
            assertTrue(haClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build()).size() > 0);
        }

        try (GetObjectResponse resp = haClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            assertEquals("e2e-dual-alias", new String(resp.readAllBytes(), StandardCharsets.UTF_8));
        }

        haClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
