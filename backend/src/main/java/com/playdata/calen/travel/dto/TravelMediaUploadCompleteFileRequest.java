package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TravelMediaUploadCompleteFileRequest(
        @NotBlank String objectKey,
        @NotBlank String originalFileName,
        @NotBlank String contentType,
        @Positive long fileSize
) {
}
