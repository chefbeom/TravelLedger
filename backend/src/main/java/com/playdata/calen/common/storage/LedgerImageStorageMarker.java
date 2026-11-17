package com.playdata.calen.common.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Operator-provisioned marker; it is evidence of configuration, not deletion authority. */
public final class LedgerImageStorageMarker {
    public static final String OBJECT_KEY = ".calen-ledger-storage-id";
    private static final int MAX_MARKER_BYTES = 128;

    private LedgerImageStorageMarker() {}

    public static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{16,80}");
    }

    public static boolean matches(MinioClient client, String bucket, String expectedId) {
        if (client == null || bucket == null || bucket.isBlank() || !validId(expectedId)) return false;
        try (InputStream marker = client.getObject(GetObjectArgs.builder().bucket(bucket).object(OBJECT_KEY).build())) {
            byte[] contents = marker.readNBytes(MAX_MARKER_BYTES + 1);
            if (contents.length > MAX_MARKER_BYTES) return false;
            String actual = new String(contents, StandardCharsets.UTF_8).trim();
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expectedId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            // Do not reveal provider details or fail a normal image upload because this optional marker is unavailable.
            return false;
        }
    }
}
