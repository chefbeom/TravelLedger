package com.playdata.calen.travel.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TravelShareGroupResponse(
        Long id,
        String name,
        List<TravelShareRecipientResponse> recipients,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
