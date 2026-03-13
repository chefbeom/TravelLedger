package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelMediaUploadPrepareResponse(
        String uploadMode,
        List<TravelMediaUploadTargetResponse> uploads
) {
}
