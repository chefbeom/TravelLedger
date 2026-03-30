package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;

public record SupportInquiryReplyRequest(
        @NotBlank(message = "답변 내용을 입력해 주세요.")
        String content
) {
}
