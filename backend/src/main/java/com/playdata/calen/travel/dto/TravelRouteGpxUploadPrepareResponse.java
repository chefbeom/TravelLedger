package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelRouteGpxUploadPrepareResponse(
        String uploadMode,
        List<TravelRouteGpxUploadTargetResponse> uploads
) {
}
