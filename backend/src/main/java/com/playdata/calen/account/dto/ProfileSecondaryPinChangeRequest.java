package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileSecondaryPinChangeRequest(
        @NotBlank(message = "현재 2차 비밀번호는 필수입니다.")
        String secondaryPin,
        @NotBlank(message = "새 2차 비밀번호는 필수입니다.")
        String newSecondaryPin
) {
}
