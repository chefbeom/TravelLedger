package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserNotificationCreateRequest(
        @NotBlank @Size(max = 60) String type,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 1000) String message,
        @Size(max = 500) String targetUrl,
        String metadataJson
) {
}