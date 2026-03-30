package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record SupportInquiryResponse(
        Long id,
        String title,
        String content,
        String status,
        LocalDateTime createdAt,
        String senderLoginId,
        String senderDisplayName,
        String attachmentFileName,
        String attachmentContentType,
        String attachmentUrl,
        String replyContent,
        LocalDateTime repliedAt,
        String repliedByLoginId,
        String repliedByDisplayName
) {
}
