package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TravelPlanSummaryResponse(
        Long id,
        String name,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String homeCurrency,
        Integer headCount,
        String status,
        String colorHex,
        String memo,
        BigDecimal plannedTotalKrw,
        BigDecimal actualTotalKrw,
        int budgetItemCount,
        int recordCount,
        int memoryRecordCount,
        int mediaItemCount,
        int routeSegmentCount,
        BigDecimal totalDistanceKm,
        Integer totalDurationMinutes,
        Integer totalStepCount
) {
}
