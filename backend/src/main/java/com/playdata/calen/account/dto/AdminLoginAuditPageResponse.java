package com.playdata.calen.account.dto;

import java.util.List;

public record AdminLoginAuditPageResponse(
        List<AdminLoginAuditResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
