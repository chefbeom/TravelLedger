package com.playdata.calen.account.dto;

public record EmailVerificationResultResponse(
        boolean verified,
        String message
) {
}
