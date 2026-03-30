package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;

public record TravelPlanShareResponse(
        Long id,
        Long planId,
        String planName,
        String recipientLoginId,
        String recipientDisplayName,
        LocalDateTime sharedAt
) {
}
