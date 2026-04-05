package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record TravelMediaUploadCompleteFileRequest(
        @NotBlank String objectKey,
        @NotBlank String originalFileName,
        @NotBlank String contentType,
        @Positive long fileSize,
        List<@jakarta.validation.Valid TravelPreparedThumbnailUploadCompleteRequest> thumbnails,
        BigDecimal gpsLatitude,
        BigDecimal gpsLongitude
) {
}
