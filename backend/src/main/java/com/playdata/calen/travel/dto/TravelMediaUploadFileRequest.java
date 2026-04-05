package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record TravelMediaUploadFileRequest(
        @NotBlank String originalFileName,
        @NotBlank String contentType,
        @Positive long fileSize,
        List<@jakarta.validation.Valid TravelPreparedThumbnailUploadRequest> thumbnails
) {
}
