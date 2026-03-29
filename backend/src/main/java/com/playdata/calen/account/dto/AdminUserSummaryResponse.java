package com.playdata.calen.account.dto;

public record AdminUserSummaryResponse(
        Long id,
        String loginId,
        String displayName,
        String role,
        boolean admin,
        boolean active
) {
}
