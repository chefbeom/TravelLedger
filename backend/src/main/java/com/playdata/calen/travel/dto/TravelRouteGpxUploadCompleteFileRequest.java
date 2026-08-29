package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TravelRouteGpxUploadCompleteFileRequest(
        @NotBlank String objectKey,
        @NotBlank String originalFileName,
        String contentType,
        @Positive long fileSize
) {
}
