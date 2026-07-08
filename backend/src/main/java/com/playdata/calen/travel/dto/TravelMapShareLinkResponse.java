package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TravelMapShareLinkResponse(
        String token,
        String title,
        List<Long> planIds,
        List<Long> excludedRecordIds,
        List<Long> excludedMediaIds,
        List<Long> excludedRouteIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
