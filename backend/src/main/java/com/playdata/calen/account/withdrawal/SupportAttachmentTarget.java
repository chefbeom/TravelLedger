package com.playdata.calen.account.withdrawal;

import java.util.regex.Pattern;

/** Encrypted task payload for one exact support file, never a directory deletion target. */
public record SupportAttachmentTarget(Long ownerId, String path, String storageId, String sha256) {
    private static final Pattern FILE_NAME = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|jpeg|gif|webp|bmp)$");

    public static boolean validPath(Long ownerId, String path) {
        if (ownerId == null || ownerId <= 0 || path == null) return false;
        String prefix = "user-" + ownerId + "/";
        return path.startsWith(prefix) && FILE_NAME.matcher(path.substring(prefix.length())).matches();
    }

    public boolean valid() {
        return validPath(ownerId, path) && storageId != null && storageId.matches("[A-Za-z0-9_-]{16,80}")
                && sha256 != null && sha256.matches("[0-9a-f]{64}");
    }

    @Override public String toString() { return "SupportAttachmentTarget[redacted]"; }
}
