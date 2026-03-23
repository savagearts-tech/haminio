package io.github.haminio.interceptor;

import io.github.haminio.endpoint.Endpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry that maps a MinIO multipart upload {@code uploadId}
 * to the originating endpoint for session affinity.
 * <p>
 * This is required because MinIO's {@code uploadId} is bound to the node
 * that created it: UploadPart and CompleteMultipartUpload MUST be sent
 * to the same endpoint as the CreateMultipartUpload.
 */
public class MultipartSessionRegistry {

    private final ConcurrentMap<String, Endpoint> sessions = new ConcurrentHashMap<>();

    public void register(String uploadId, Endpoint endpoint) {
        sessions.put(uploadId, endpoint);
    }

    public Endpoint get(String uploadId) {
        return sessions.get(uploadId);
    }

    public void remove(String uploadId) {
        sessions.remove(uploadId);
    }

    public int size() {
        return sessions.size();
    }
}
