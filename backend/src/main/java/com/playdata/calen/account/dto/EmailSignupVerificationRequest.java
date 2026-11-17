package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailSignupVerificationRequest(
        @NotBlank(message = "이메일 인증 토큰은 필수입니다.") String token,
        @NotBlank(message = "가입 시 설정한 2차 비밀번호를 입력해 주세요.")
        @Pattern(regexp = "[0-9]{8}", message = "2차 비밀번호는 숫자 8자리여야 합니다.") String secondaryPin
) {}
