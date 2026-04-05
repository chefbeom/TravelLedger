package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TravelPreparedThumbnailUploadRequest(
        @NotBlank String variant,
        @NotBlank String contentType,
        @Positive long fileSize
) {
}
