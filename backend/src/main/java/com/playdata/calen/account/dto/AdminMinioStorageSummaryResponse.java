package com.playdata.calen.account.dto;

public record AdminMinioStorageSummaryResponse(
        boolean available,
        String bucketName,
        long objectCount,
        long totalSizeBytes,
        String errorMessage
) {
}
