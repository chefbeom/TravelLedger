package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminAccessVerifyRequest(
        @NotBlank(message = "2차 비밀번호를 입력해 주세요.")
        @Pattern(regexp = "\\d{8}", message = "2차 비밀번호는 숫자 8자리여야 합니다.")
        String code
) {
}
