package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelMyMapMarkerDetailBundleResponse(
        Long selectedMarkerId,
        List<TravelMyMapMarkerDetailResponse> markers
) {
}
