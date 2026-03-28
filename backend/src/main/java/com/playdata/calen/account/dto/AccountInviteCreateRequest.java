package com.playdata.calen.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AccountInviteCreateRequest(
        @Min(value = 1, message = "초대 링크 유효 시간은 최소 1시간입니다.")
        @Max(value = 168, message = "초대 링크 유효 시간은 최대 168시간입니다.")
        Integer expiresInHours
) {
}
