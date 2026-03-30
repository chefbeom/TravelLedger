package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerEntryPageResponse(
        List<LedgerEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
