package com.playdata.calen.account.dto;

public record AdminMinioStorageSummaryResponse(
        boolean available,
        String bucketName,
        long objectCount,
        long totalSizeBytes,
        long capacityBytes,
        long remainingBytes,
        double usedPercent,
        String errorMessage
) {
}