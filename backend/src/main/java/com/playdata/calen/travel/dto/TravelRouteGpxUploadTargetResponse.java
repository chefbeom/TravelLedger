package com.playdata.calen.travel.dto;

public record TravelRouteGpxUploadTargetResponse(
        String method,
        String uploadUrl,
        String objectKey,
        String storedFileName,
        String originalFileName,
        String contentType,
        long fileSize
) {
}
