package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelPortfolioResponse(
        String scopeType,
        String scopeLabel,
        String name,
        String homeCurrency,
        Integer headCount,
        BigDecimal plannedTotalKrw,
        BigDecimal actualTotalKrw,
        int budgetItemCount,
        int recordCount,
        int memoryRecordCount,
        int mediaItemCount,
        int routeSegmentCount,
        BigDecimal totalDistanceKm,
        Integer totalDurationMinutes,
        Integer totalStepCount,
        int includedPlanCount,
        List<TravelPlanSummaryResponse> plans,
        List<TravelBudgetItemResponse> budgetItems,
        List<TravelExpenseRecordResponse> records,
        List<TravelMemoryRecordResponse> memoryRecords,
        List<TravelMediaResponse> mediaItems,
        List<TravelRouteSegmentResponse> routeSegments
) {
}
