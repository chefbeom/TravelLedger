package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;

public record TravelSharedExhibitDetailResponse(
        Long id,
        String sharedByLoginId,
        String sharedByDisplayName,
        LocalDateTime sharedAt,
        TravelPlanDetailResponse travelPlan
) {
}
