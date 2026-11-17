package com.playdata.calen.ledger.domain.model;

import java.time.LocalDateTime;

/** Stored input references/content, without storage SDK or HTTP multipart types. */
public final class LedgerImageStorageData {
    private LedgerImageStorageData() {}
    public record StoredImage(String objectKey, LocalDateTime storedAt, String bucket, String storageId, String versionId, String etag) {
        // Existing and non-MinIO storage adapters may not know a historical bucket.
        public StoredImage(String objectKey, LocalDateTime storedAt) { this(objectKey, storedAt, null, null, null, null); }
        public StoredImage(String objectKey, LocalDateTime storedAt, String bucket) { this(objectKey, storedAt, bucket, null, null, null); }
        public StoredImage(String objectKey, LocalDateTime storedAt, String bucket, String storageId, String versionId) {
            this(objectKey, storedAt, bucket, storageId, versionId, null);
        }
        @Override public String toString() { return "StoredImage[redacted]"; }
    }
    public record StoredImageContent(byte[] bytes, String contentType, String fileName) {}
}
