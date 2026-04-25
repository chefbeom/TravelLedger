package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TravelPublicTripSummaryResponse(
        Long planId,
        String planName,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String colorHex,
        String sharedByLoginId,
        String sharedByDisplayName,
        LocalDateTime publicSharedAt,
        int memoryRecordCount,
        int mediaItemCount,
        int routeSegmentCount,
        BigDecimal totalDistanceKm,
        String representativePhotoUrl
) {
}
