package com.playdata.calen.account.dto;

public record AdminSummaryResponse(
        long totalUsers,
        long activeUsers,
        long adminUsers,
        long totalInvites,
        long pendingInvites,
        long recentFailureCount,
        int blockedIpCount
) {
}
