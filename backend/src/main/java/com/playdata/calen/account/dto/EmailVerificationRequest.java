package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;

public record EmailVerificationRequest(
        @NotBlank(message = "이메일 인증 토큰은 필수입니다.")
        String token
) {
}
