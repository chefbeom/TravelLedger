package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record UserNotificationResponse(
        Long id,
        String type,
        String title,
        String message,
        String targetUrl,
        String metadataJson,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
}