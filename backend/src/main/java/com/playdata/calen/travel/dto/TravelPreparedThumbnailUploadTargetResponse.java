package com.playdata.calen.travel.dto;

public record TravelPreparedThumbnailUploadTargetResponse(
        String variant,
        String method,
        String uploadUrl,
        String objectKey,
        String contentType,
        long fileSize
) {
}
