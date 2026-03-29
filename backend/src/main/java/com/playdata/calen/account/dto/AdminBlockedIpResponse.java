package com.playdata.calen.account.dto;

public record AdminBlockedIpResponse(
        String clientIp,
        int failureCount,
        String lockedUntil
) {
}
