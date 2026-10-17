package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelPlanStatus;
import java.time.LocalDate;
import java.math.BigDecimal;

public final class TravelPlanData {
    private TravelPlanData() {}

    public record Draft(String name, String destination, LocalDate startDate, LocalDate endDate, String homeCurrency,
            Integer headCount, String status, String colorHex, String memo) {}
    public record Value(String name, String destination, LocalDate startDate, LocalDate endDate, String homeCurrency,
            Integer headCount, TravelPlanStatus status, String colorHex, String memo) {}
    public record Metrics(BigDecimal plannedTotalKrw, BigDecimal actualTotalKrw, int budgetItemCount, int recordCount,
            int memoryRecordCount, int mediaItemCount, int routeSegmentCount, BigDecimal totalDistanceKm,
            Integer totalDurationMinutes, Integer totalStepCount) {
        public static Metrics empty() {
            return new Metrics(new BigDecimal("0.00"), new BigDecimal("0.00"), 0, 0, 0, 0, 0, new BigDecimal("0.000"), 0, 0);
        }
    }
    public record Summary(Long id, Value value, Metrics metrics) {}
}
