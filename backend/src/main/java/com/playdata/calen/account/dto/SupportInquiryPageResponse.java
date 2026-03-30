package com.playdata.calen.account.dto;

import java.util.List;

public record SupportInquiryPageResponse(
        List<SupportInquiryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
