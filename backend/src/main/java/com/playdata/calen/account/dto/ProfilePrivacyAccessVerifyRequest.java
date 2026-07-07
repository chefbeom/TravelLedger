package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfilePrivacyAccessVerifyRequest(
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password,
        @NotBlank(message = "2차 비밀번호는 필수입니다.")
        String secondaryPin
) {
}
