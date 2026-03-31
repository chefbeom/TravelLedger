package com.playdata.calen.account.dto;

public record AdminBackupFileResponse(
        String fileName,
        long sizeBytes,
        String modifiedAt
) {
}
