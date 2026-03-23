package io.github.haminio.interceptor;

import io.github.haminio.client.LoadBalancer;
import io.github.haminio.endpoint.Endpoint;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * OkHttp Application Interceptor that rewrites the outgoing request URL
 * to the next healthy endpoint selected by the {@link LoadBalancer}.
 * <p>
 * This interceptor also manages Multipart Upload Session Affinity:
 * once a multipart CreateMultipartUpload is initiated, the uploadId is bound
 * to the originating endpoint via {@link MultipartSessionRegistry}.
 * All subsequent UploadPart / CompleteMultipartUpload / AbortMultipartUpload
 * calls for the same uploadId are pinned to that endpoint.
 * <p>
 * Connection management is NOT performed here — all TCP connection reuse
 * is handled by OkHttp's ConnectionPool.
 */
public class LoadBalancingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingInterceptor.class);

    private final LoadBalancer loadBalancer;
    private final MultipartSessionRegistry multipartRegistry;

    public LoadBalancingInterceptor(LoadBalancer loadBalancer, MultipartSessionRegistry multipartRegistry) {
        this.loadBalancer = Objects.requireNonNull(loadBalancer);
        this.multipartRegistry = Objects.requireNonNull(multipartRegistry);
    }

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // 1. Check for multipart session affinity
        String uploadId = extractUploadId(original);
        Endpoint stickyEndpoint = uploadId != null ? multipartRegistry.get(uploadId) : null;

        Endpoint target = stickyEndpoint != null ? stickyEndpoint : loadBalancer.next();
        if (target == null) {
            throw new IOException("No healthy MinIO endpoints available");
        }

        Request rewritten = rewrite(original, target);
        log.debug("Routing {} {} → {}", original.method(), original.url().encodedPath(), target);
        Response response = chain.proceed(rewritten);

        // 2. Register new multipart session after successful CreateMultipartUpload
        if (isCreateMultipartUploadResponse(original, response)) {
            String newUploadId = extractUploadIdFromResponse(response);
            if (newUploadId != null) {
                multipartRegistry.register(newUploadId, target);
                log.debug("Registered multipart session uploadId={} → {}", newUploadId, target);
                // buffer and reconstruct the response so the body can still be read downstream
                return bufferResponse(response);
            }
        }
        // 3. Clean up completed / aborted multipart sessions
        if (uploadId != null && isTerminalMultipartOperation(original)) {
            multipartRegistry.remove(uploadId);
        }

        return response;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Request rewrite(Request original, Endpoint target) {
        HttpUrl originalUrl = original.url();
        HttpUrl.Builder newUrl = HttpUrl.parse(target.url()).newBuilder()
                .encodedPath(originalUrl.encodedPath())
                .encodedQuery(originalUrl.encodedQuery());
        return original.newBuilder()
                .url(newUrl.build())
                .header("Host", HttpUrl.parse(target.url()).host())
                .build();
    }

    /** Extracts uploadId query parameter from the request URL, if present. */
    private String extractUploadId(Request req) {
        return req.url().queryParameter("uploadId");
    }

    /** True if the request is UploadPart, CompleteMultipartUpload, or AbortMultipartUpload. */
    private boolean isTerminalMultipartOperation(Request req) {
        String path = req.url().encodedPath();
        String query = req.url().encodedQuery();
        // Complete/Abort: POST/DELETE with uploadId and no partNumber
        return query != null && query.contains("uploadId=")
                && !query.contains("partNumber=")
                && (req.method().equals("POST") || req.method().equals("DELETE"));
    }

    private boolean isCreateMultipartUploadResponse(Request req, Response resp) {
        // CreateMultipartUpload: POST to object path with ?uploads
        String query = req.url().encodedQuery();
        return resp.isSuccessful()
                && req.method().equals("POST")
                && query != null && query.equals("uploads");
    }

    private String extractUploadIdFromResponse(Response response) throws IOException {
        // Parse <UploadId>...</UploadId> from the XML response body
        okhttp3.ResponseBody body = response.peekBody(Long.MAX_VALUE);
        if (body == null) return null;
        String xml = body.string();
        int start = xml.indexOf("<UploadId>");
        int end = xml.indexOf("</UploadId>");
        if (start < 0 || end < 0) return null;
        return xml.substring(start + "<UploadId>".length(), end);
    }

    private Response bufferResponse(Response response) throws IOException {
        byte[] bytes = response.body().bytes();
        okhttp3.ResponseBody newBody = okhttp3.ResponseBody.create(bytes, response.body().contentType());
        return response.newBuilder().body(newBody).build();
    }
}
