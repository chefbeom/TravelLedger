package com.playdata.calen.account.dto;

public record AdminInviteSummaryResponse(
        Long id,
        String createdAt,
        String expiresAt,
        String usedAt,
        String status,
        String createdByLoginId,
        String createdByDisplayName,
        String usedByLoginId,
        String usedByDisplayName
) {
}
