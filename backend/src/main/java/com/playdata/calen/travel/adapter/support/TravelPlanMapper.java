package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;

public final class TravelPlanMapper {
    private TravelPlanMapper() {}
    public static Draft draft(TravelPlanRequest request) {
        return new Draft(request.name(), request.destination(), request.startDate(), request.endDate(), request.homeCurrency(),
                request.headCount(), request.status(), request.colorHex(), request.memo());
    }
    public static TravelPlanSummaryResponse response(Summary result) {
        var p = result.value(); var m = result.metrics();
        return new TravelPlanSummaryResponse(result.id(), p.name(), p.destination(), p.startDate(), p.endDate(), p.homeCurrency(),
                p.headCount(), p.status().name(), p.colorHex(), p.memo(), m.plannedTotalKrw(), m.actualTotalKrw(), m.budgetItemCount(),
                m.recordCount(), m.memoryRecordCount(), m.mediaItemCount(), m.routeSegmentCount(), m.totalDistanceKm(), m.totalDurationMinutes(), m.totalStepCount());
    }
}
