package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelMyMapMarkerSummaryResponse(
        Long id,
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
        BigDecimal longitude
) {
}
