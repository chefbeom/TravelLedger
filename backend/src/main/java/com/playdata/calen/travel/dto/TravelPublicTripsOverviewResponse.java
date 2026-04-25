package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelPublicTripsOverviewResponse(
        int includedPlanCount,
        int photoMarkerCount,
        int photoClusterCount,
        int routeCount,
        BigDecimal totalDistanceKm,
        List<TravelPublicTripSummaryResponse> plans,
        List<TravelPublicPhotoClusterSummaryResponse> photoClusters,
        List<TravelPublicPhotoPinResponse> photoPins,
        List<TravelRouteSegmentResponse> routes
) {
}
