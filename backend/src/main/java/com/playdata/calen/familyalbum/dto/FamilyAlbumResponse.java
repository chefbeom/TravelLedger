package com.playdata.calen.familyalbum.dto;

import java.time.LocalDateTime;

public record FamilyAlbumResponse(
        Long id,
        Long categoryId,
        String categoryName,
        Long ownerId,
        String ownerName,
        String title,
        String description,
        LocalDateTime createdAt,
        int itemCount
) {
}
