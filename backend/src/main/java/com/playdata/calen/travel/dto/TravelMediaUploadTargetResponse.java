package com.playdata.calen.travel.dto;

public record TravelMediaUploadTargetResponse(
        String method,
        String uploadUrl,
        String objectKey,
        String storedFileName,
        String originalFileName,
        String contentType,
        long fileSize,
        java.util.List<TravelPreparedThumbnailUploadTargetResponse> thumbnails
) {
}
