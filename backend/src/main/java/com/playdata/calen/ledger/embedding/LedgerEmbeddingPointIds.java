package com.playdata.calen.ledger.embedding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** UUIDv5 namespace and canonical name must not change for the existing collection. */
public final class LedgerEmbeddingPointIds {

    public static final UUID NAMESPACE = UUID.fromString("2d71c792-fd86-49da-81c7-a6c354eaf123");

    private LedgerEmbeddingPointIds() { }

    public static UUID forRecord(Long ownerId, LedgerEmbeddingRecordType recordType, Long sourceId) {
        requireId(ownerId, "ownerId");
        requireId(sourceId, "sourceId");
        Objects.requireNonNull(recordType, "recordType is required");
        String name = "ledger|" + ownerId + "|" + recordType.metadataValue() + "|" + sourceId;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] namespaceBytes = ByteBuffer.allocate(16)
                    .putLong(NAMESPACE.getMostSignificantBits()).putLong(NAMESPACE.getLeastSignificantBits()).array();
            sha1.update(namespaceBytes);
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("UUIDv5 requires SHA-1 support", exception);
        }
    }

    private static void requireId(Long value, String label) {
        if (value == null || value <= 0) throw new IllegalArgumentException(label + " must be a persisted positive ID");
    }
}
