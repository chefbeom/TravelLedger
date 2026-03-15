package com.playdata.calen.familyalbum.dto;

import java.time.LocalDateTime;

public record FamilyMediaResponse(
        Long id,
        Long categoryId,
        String categoryName,
        Long ownerId,
        String ownerName,
        String mediaType,
        String originalFileName,
        String contentType,
        long fileSize,
        String caption,
        boolean shared,
        LocalDateTime capturedAt,
        LocalDateTime uploadedAt,
        String contentUrl
) {
}
