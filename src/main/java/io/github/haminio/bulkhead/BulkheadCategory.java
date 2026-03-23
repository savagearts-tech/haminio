package io.github.haminio.bulkhead;

/**
 * Classifies a MinIO request into SMALL or LARGE based on Content-Length.
 * <p>
 * Contract (per spec):
 * - Content-Length >= threshold → LARGE
 * - Content-Length < threshold  → SMALL
 * - Content-Length unknown (-1) → LARGE (conservative, per spec)
 */
public class BulkheadCategory {
    public enum Category { SMALL, LARGE }

    private final long thresholdBytes;

    public BulkheadCategory(long thresholdBytes) {
        this.thresholdBytes = thresholdBytes;
    }

    public Category classify(long contentLength) {
        // -1 means unknown — conservative routing to LARGE pool
        if (contentLength < 0 || contentLength >= thresholdBytes) {
            return Category.LARGE;
        }
        return Category.SMALL;
    }
}
