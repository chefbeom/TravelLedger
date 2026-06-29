package com.playdata.calen.account.dto;

public record AdminLoginAuditResponse(
        Long id,
        String attemptedAt,
        String loginId,
        String clientIp,
        String userAgent,
        String status,
        String detail,
        boolean success,
        Long userId,
        String displayName,
        boolean admin
) {
}
