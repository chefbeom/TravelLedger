package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record UserNotificationReadResponse(
        int updatedCount,
        long unreadCount,
        LocalDateTime processedAt
) {
}