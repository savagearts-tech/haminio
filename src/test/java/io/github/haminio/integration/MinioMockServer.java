package io.github.haminio.integration;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight fake MinIO node backed by {@link MockWebServer}.
 * <p>
 * Handles the S3-compatible HTTP operations exercised by the integration tests:
 * <ul>
 *   <li>{@code HEAD /{bucket}}        – bucketExists (200 / 404)</li>
 *   <li>{@code PUT /{bucket}}         – makeBucket (200)</li>
 *   <li>{@code PUT /{bucket}/{key}}   – putObject (200 + ETag)</li>
 *   <li>{@code HEAD /{bucket}/{key}}  – statObject (200 + metadata)</li>
 *   <li>{@code GET /{bucket}/{key}}   – getObject (200 + body)</li>
 *   <li>{@code DELETE /{bucket}/{key}}– removeObject (204)</li>
 *   <li>{@code GET /minio/health/ready} – health probe (200)</li>
 * </ul>
 * AWS Signature V4 authentication headers are accepted but never validated.
 * Each instance maintains its own independent in-memory object store.
 */
public final class MinioMockServer {

    /** bucket → (key → bytes) — package-private for direct test access */
    final Map<String, Map<String, byte[]>> store = new ConcurrentHashMap<>();
    /** Underlying server — package-private for port inspection in tests */
    final MockWebServer server;

    public MinioMockServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new S3Dispatcher());
        // Bind explicitly to loopback to avoid Windows resolving getHostName() to
        // "kubernetes.docker.internal" or other non-loopback addresses.
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
    }

    /** Base URL that should be passed to MinIO SDK / HaMinioClientConfig. */
    public String url() {
        return "http://127.0.0.1:" + server.getPort();
    }

    public void close() throws IOException {
        server.shutdown();
    }

    // ── Package-level store helpers (for direct test manipulation) ────────────

    /** Directly insert an object into the store (bypassing HTTP). */
    void putRaw(String bucket, String key, byte[] data) {
        store.computeIfAbsent(bucket, b -> new ConcurrentHashMap<>()).put(key, data.clone());
    }

    /** Directly read an object from the store; returns {@code null} if absent. */
    byte[] getRaw(String bucket, String key) {
        Map<String, byte[]> bkt = store.get(bucket);
        return bkt == null ? null : bkt.get(key);
    }

    /** Returns {@code true} if the key exists in the store. */
    boolean existsRaw(String bucket, String key) {
        return getRaw(bucket, key) != null;
    }

    /** Directly remove an object from the store. */
    void removeRaw(String bucket, String key) {
        Map<String, byte[]> bkt = store.get(bucket);
        if (bkt != null) bkt.remove(key);
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private final class S3Dispatcher extends Dispatcher {

        private static final DateTimeFormatter RFC1123 = DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String method = req.getMethod();
            String path   = req.getPath();
            if (path == null) path = "/";

            // Separate path from query string
            int qMark    = path.indexOf('?');
            String pathOnly = qMark < 0 ? path : path.substring(0, qMark);
            String query    = qMark < 0 ? ""   : path.substring(qMark + 1); // e.g. "location="

            // Health-check endpoint (no bucket involved)
            if ("/minio/health/ready".equals(pathOnly)) {
                return ok200();
            }

            // Split "/{bucket}" or "/{bucket}/{key}"
            String stripped = pathOnly.startsWith("/") ? pathOnly.substring(1) : pathOnly;
            int slash = stripped.indexOf('/');
            String bucket = slash < 0 ? stripped : stripped.substring(0, slash);
            String key    = slash < 0 ? ""        : stripped.substring(slash + 1);

            if (bucket.isEmpty()) {
                return s3Error(404, "NoSuchBucket", "No bucket specified", "");
            }

            return switch (method) {
                case "HEAD"   -> key.isEmpty() ? headBucket(bucket) : headObject(bucket, key);
                case "GET"    -> key.isEmpty() ? getBucketOp(bucket, query) : getObject(bucket, key);
                case "PUT"    -> key.isEmpty() ? putBucket(bucket) : putObject(bucket, key, req);
                case "DELETE" -> deleteObject(bucket, key);
                default       -> new MockResponse().setResponseCode(405);
            };
        }

        // ── Bucket operations ─────────────────────────────────────────────────

        private MockResponse headBucket(String bucket) {
            if (store.containsKey(bucket)) {
                return ok200();
            }
            return s3Error(404, "NoSuchBucket", "The specified bucket does not exist", bucket);
        }

        private MockResponse putBucket(String bucket) {
            store.computeIfAbsent(bucket, b -> new ConcurrentHashMap<>());
            return ok200();
        }

        /**
         * Handles bucket-level GET requests (no object key in path).
         * The MinIO SDK always calls {@code GET /{bucket}?location=} before the first
         * operation to resolve the bucket's region. We respond with the default region.
         */
        private MockResponse getBucketOp(String bucket, String query) {
            if (!store.containsKey(bucket)) {
                return s3Error(404, "NoSuchBucket", "The specified bucket does not exist", bucket);
            }
            // GetBucketLocation
            if ("location=".equals(query) || query.startsWith("location=")) {
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"></LocationConstraint>";
                return new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/xml")
                        .setBody(xml);
            }
            // Any other bucket-level GET (ListObjects, etc.) — return empty list
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    "<Name>" + bucket + "</Name><Prefix></Prefix><MaxKeys>1000</MaxKeys>" +
                    "<IsTruncated>false</IsTruncated></ListBucketResult>";
            return new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/xml")
                    .setBody(xml);
        }

        // ── Object operations ─────────────────────────────────────────────────

        private MockResponse putObject(String bucket, String key, RecordedRequest req) {
            if (!store.containsKey(bucket)) {
                return s3Error(404, "NoSuchBucket", "The specified bucket does not exist", bucket);
            }
            byte[] body = req.getBody().readByteArray();
            store.get(bucket).put(key, body);
            String etag = etag(body);
            return new MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", etag)
                    .addHeader("x-amz-request-id", "mock0000request00id");
        }

        private MockResponse headObject(String bucket, String key) {
            byte[] data = getRaw(bucket, key);
            if (data == null) {
                return s3Error(404, "NoSuchKey", "The specified key does not exist", key);
            }
            return new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length",  String.valueOf(data.length))
                    .addHeader("Content-Type",    "application/octet-stream")
                    .addHeader("ETag",             etag(data))
                    .addHeader("Last-Modified",    now())
                    .addHeader("x-amz-request-id","mock0000request00id");
        }

        private MockResponse getObject(String bucket, String key) {
            byte[] data = getRaw(bucket, key);
            if (data == null) {
                return s3Error(404, "NoSuchKey", "The specified key does not exist", key);
            }
            return new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type",    "application/octet-stream")
                    .addHeader("Content-Length",  String.valueOf(data.length))
                    .addHeader("ETag",             etag(data))
                    .addHeader("Last-Modified",    now())
                    .addHeader("Accept-Ranges",    "bytes")
                    .addHeader("x-amz-request-id","mock0000request00id")
                    .setBody(new Buffer().write(data));
        }

        private MockResponse deleteObject(String bucket, String key) {
            Map<String, byte[]> bkt = store.get(bucket);
            if (bkt != null) bkt.remove(key);
            return new MockResponse().setResponseCode(204);
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private MockResponse ok200() {
            return new MockResponse().setResponseCode(200);
        }

        private MockResponse s3Error(int code, String errorCode, String message, String resource) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>" +
                    "<Code>" + errorCode + "</Code>" +
                    "<Message>" + message + "</Message>" +
                    "<Resource>/" + resource + "</Resource>" +
                    "</Error>";
            return new MockResponse()
                    .setResponseCode(code)
                    .addHeader("Content-Type", "application/xml")
                    .setBody(xml);
        }

        private String etag(byte[] data) {
            return "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
        }

        private String now() {
            return ZonedDateTime.now(ZoneOffset.UTC).format(RFC1123);
        }
    }
}
