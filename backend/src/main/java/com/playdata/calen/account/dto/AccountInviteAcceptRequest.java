package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountInviteAcceptRequest(
        @NotBlank(message = "초대 토큰은 필수입니다.")
        String token,
        @NotBlank(message = "로그인 ID는 필수입니다.")
        String loginId,
        @NotBlank(message = "표시 이름은 필수입니다.")
        String displayName,
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        String password,
        @NotBlank(message = "2차 비밀번호는 필수입니다.")
        @Size(min = 8, max = 8, message = "2차 비밀번호는 숫자 8자리여야 합니다.")
        String secondaryPin
) {
}
