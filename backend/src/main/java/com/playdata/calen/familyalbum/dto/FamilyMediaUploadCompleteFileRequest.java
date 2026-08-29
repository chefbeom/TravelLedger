package com.playdata.calen.familyalbum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record FamilyMediaUploadCompleteFileRequest(
        @NotBlank String objectKey,
        @NotBlank String originalFileName,
        String contentType,
        @Positive long fileSize
) {
}
