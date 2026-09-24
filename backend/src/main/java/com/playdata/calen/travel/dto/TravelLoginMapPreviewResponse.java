package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelLoginMapPreviewResponse(
        boolean enabled,
        String title,
        int markerCount,
        int routeCount,
        List<TravelLoginMapPreviewMarkerResponse> markers,
        List<TravelLoginMapPreviewRouteResponse> routes
) {
}
