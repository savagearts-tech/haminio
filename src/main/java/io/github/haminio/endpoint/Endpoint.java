package io.github.haminio.endpoint;

import java.util.Objects;

/**
 * Immutable value object representing a registered MinIO server endpoint.
 */
public final class Endpoint {

    private final String url;

    public Endpoint(String url) {
        this.url = Objects.requireNonNull(url, "url must not be null")
                .replaceAll("/+$", ""); // strip trailing slashes
    }

    public String url() {
        return url;
    }

    /** Returns the health-check probe URL for this endpoint. */
    public String healthCheckUrl() {
        return url + "/minio/health/ready";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Endpoint e)) return false;
        return url.equals(e.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    @Override
    public String toString() {
        return "Endpoint{" + url + "}";
    }
}
