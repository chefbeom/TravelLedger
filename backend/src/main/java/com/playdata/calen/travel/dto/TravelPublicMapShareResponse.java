package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TravelPublicMapShareResponse(
        String token,
        String title,
        String ownerLoginId,
        String ownerDisplayName,
        List<Long> planIds,
        LocalDateTime createdAt,
        TravelMyMapOverviewResponse overview
) {
}
