package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record AccountInviteCreateResponse(
        String token,
        LocalDateTime expiresAt
) {
}
