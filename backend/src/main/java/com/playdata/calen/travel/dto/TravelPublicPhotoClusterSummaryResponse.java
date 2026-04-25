package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TravelPublicPhotoClusterSummaryResponse(
        Long id,
        Long representativeMediaId,
        Long representativeRecordId,
        Long planId,
        String planName,
        String planColorHex,
        LocalDate memoryDate,
        LocalTime memoryTime,
        String category,
        String title,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        int photoCount,
        int memoryCount,
        BigDecimal maxDistanceMeters,
        boolean representativeOverride,
        String representativePhotoUrl,
        String sharedByLoginId,
        String sharedByDisplayName,
        LocalDateTime publicSharedAt
) {
}
