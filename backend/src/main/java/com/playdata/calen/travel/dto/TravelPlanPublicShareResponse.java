package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;

public record TravelPlanPublicShareResponse(
        Long planId,
        Boolean publicShared,
        LocalDateTime publicSharedAt
) {
}
