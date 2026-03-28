package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record AccountInviteDetailsResponse(
        String inviterDisplayName,
        LocalDateTime expiresAt
) {
}
