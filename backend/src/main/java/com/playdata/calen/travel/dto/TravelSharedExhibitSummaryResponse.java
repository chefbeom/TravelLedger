package com.playdata.calen.travel.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TravelSharedExhibitSummaryResponse(
        Long id,
        Long planId,
        String planName,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String colorHex,
        String sharedByLoginId,
        String sharedByDisplayName,
        LocalDateTime sharedAt,
        int memoryRecordCount,
        int routeSegmentCount,
        int mediaItemCount
) {
}
