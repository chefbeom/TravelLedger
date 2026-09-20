package com.playdata.calen.account.dto;

public record EmailVerificationResendResponse(
        boolean accepted,
        String message
) {
}
