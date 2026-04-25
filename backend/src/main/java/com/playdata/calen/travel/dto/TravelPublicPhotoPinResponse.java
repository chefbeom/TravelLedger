package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TravelPublicPhotoPinResponse(
        Long mediaId,
        Long clusterId,
        Long recordId,
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
        String photoUrl,
        boolean representative,
        boolean representativeOverride,
        String sharedByLoginId,
        String sharedByDisplayName,
        LocalDateTime publicSharedAt
) {
}
