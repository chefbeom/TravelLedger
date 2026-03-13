package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelCommunityPostResponse(
        Long memoryId,
        Long planId,
        String planName,
        String planColorHex,
        String ownerDisplayName,
        String title,
        String memo,
        LocalDate memoryDate,
        LocalTime memoryTime,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        String heroPhotoUrl,
        String heroPhotoCaption,
        int photoCount
) {
}
