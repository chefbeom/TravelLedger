package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountInviteAcceptRequest(
        @NotBlank(message = "Invite token is required.")
        String token,
        @NotBlank(message = "Login ID is required.")
        String loginId,
        @NotBlank(message = "Display name is required.")
        String displayName,
        @NotBlank(message = "Password is required.")
        @Size(min = 8, message = "Password must be at least 8 characters.")
        String password
) {
}
