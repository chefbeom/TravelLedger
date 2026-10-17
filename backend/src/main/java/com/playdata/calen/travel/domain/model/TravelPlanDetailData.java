package com.playdata.calen.travel.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public final class TravelPlanDetailData {
    private TravelPlanDetailData() {}
    public record Detail(TravelPlanData.Summary summary, boolean publicShared, LocalDateTime publicSharedAt,
            List<TravelBudgetData.Result> budgets, List<TravelRecordData.Result> records, List<TravelRecordData.Result> memories,
            List<TravelClusterDetailData.Media> media, List<TravelRouteData.Result> routes) {}
}
