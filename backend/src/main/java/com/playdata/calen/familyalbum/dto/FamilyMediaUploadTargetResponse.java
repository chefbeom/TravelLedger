package com.playdata.calen.familyalbum.dto;

public record FamilyMediaUploadTargetResponse(
        String method,
        String uploadUrl,
        String objectKey,
        String storedFileName,
        String originalFileName,
        String contentType,
        long fileSize,
        String mediaType
) {
}
