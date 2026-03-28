package com.playdata.calen.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AccountInviteCreateRequest(
        @Min(value = 1, message = "Invite lifetime must be at least 1 hour.")
        @Max(value = 168, message = "Invite lifetime must be 168 hours or less.")
        Integer expiresInHours
) {
}
