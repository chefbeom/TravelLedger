package com.playdata.calen.account.dto;

public record EmailVerificationStartResponse(
        boolean verificationRequired,
        String email,
        long expiresInSeconds,
        String message
) {
}
