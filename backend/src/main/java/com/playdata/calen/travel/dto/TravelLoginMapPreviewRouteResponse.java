package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelLoginMapPreviewRouteResponse(
        int number,
        List<TravelLoginMapPreviewPointResponse> points
) {
}
