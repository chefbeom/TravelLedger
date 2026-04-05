package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TravelPreparedThumbnailUploadCompleteRequest(
        @NotBlank String variant,
        @NotBlank String objectKey,
        @NotBlank String contentType,
        @Positive long fileSize
) {
}
