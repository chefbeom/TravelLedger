package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelMyMapOverviewResponse(
        int includedPlanCount,
        int markerCount,
        int routeCount,
        BigDecimal totalDistanceKm,
        List<TravelMyMapMarkerSummaryResponse> markers,
        List<TravelRouteSegmentResponse> routes
) {
}
