package com.playdata.calen.ledger.domain.model;

import java.time.LocalDateTime;

/** Stored input references/content, without storage SDK or HTTP multipart types. */
public final class LedgerImageStorageData {
    private LedgerImageStorageData() {}
    public record StoredImage(String objectKey, LocalDateTime storedAt) {}
    public record StoredImageContent(byte[] bytes, String contentType, String fileName) {}
}
