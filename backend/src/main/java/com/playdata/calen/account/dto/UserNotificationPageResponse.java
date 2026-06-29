package com.playdata.calen.account.dto;

import java.util.List;

public record UserNotificationPageResponse(
        List<UserNotificationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long unreadCount
) {
}