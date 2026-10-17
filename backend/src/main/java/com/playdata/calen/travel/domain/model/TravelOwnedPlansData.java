package com.playdata.calen.travel.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Detached facts and results; cached presentation values remain opaque and are not normalized again. */
public final class TravelOwnedPlansData {
    private TravelOwnedPlansData() {}
    public record Head(Long id, String name, String destination, LocalDate startDate, LocalDate endDate,
            String currency, Integer headCount, String status, String color, String memo) {}
    public record Summary(Head head, TravelPlanData.Metrics metrics) {}
    public record Aggregate(Head head, BigDecimal planned, BigDecimal actual, Long budgets, Long records,
            Long memories, Long media, Long routes, BigDecimal distance, Long duration, Long steps) {}
    public record Budget(Long plan, TravelBudgetData.Result value) {}
    public record Picture(TravelClusterDetailData.Media value, String url, boolean ownedUrl) {}
    public record Memory(TravelRecordData.Result value, Boolean shared) {}
    public record Rows(List<Head> plans, List<Budget> budgets, List<TravelRecordData.Result> records,
            List<TravelRecordData.Result> memories, List<TravelClusterDetailData.Media> media, List<TravelOwnedMapData.Route> routes) {}
    public record Portfolio(String scopeType, String scopeLabel, String name, String currency, Integer headCount,
            TravelPlanData.Metrics metrics, int planCount, List<Summary> plans, List<TravelBudgetData.Result> budgets,
            List<TravelRecordData.Result> records, List<Memory> memories,
            List<Picture> media, List<TravelRouteData.Result> routes) {}
}
