package com.playdata.calen.familyalbum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record FamilyMediaUploadFileRequest(
        @NotBlank String originalFileName,
        String contentType,
        @Positive long fileSize
) {
}
