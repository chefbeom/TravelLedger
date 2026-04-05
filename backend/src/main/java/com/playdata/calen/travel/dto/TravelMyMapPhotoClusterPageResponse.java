package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelMyMapPhotoClusterPageResponse(
        Long id,
        Long representativeMediaId,
        Long representativeRecordId,
        BigDecimal latitude,
        BigDecimal longitude,
        int photoCount,
        int memoryCount,
        BigDecimal maxDistanceMeters,
        boolean representativeOverride,
        TravelMediaResponse representativePhoto,
        List<TravelMediaResponse> photos,
        int page,
        int size,
        int totalPhotoCount,
        boolean hasNext
) {
}
