package com.playdata.calen.account.dto;

import jakarta.validation.constraints.Pattern;

public record AdminAccessVerifyRequest(
        @Pattern(regexp = "\\d{8}", message = "관리자 추가 비밀번호는 숫자 8자리여야 합니다.")
        String code
) {
}
