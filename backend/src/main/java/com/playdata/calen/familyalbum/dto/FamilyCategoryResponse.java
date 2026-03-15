package com.playdata.calen.familyalbum.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FamilyCategoryResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        String ownerName,
        LocalDateTime createdAt,
        int memberCount,
        long mediaCount,
        List<FamilyCategoryMemberResponse> members
) {
}
