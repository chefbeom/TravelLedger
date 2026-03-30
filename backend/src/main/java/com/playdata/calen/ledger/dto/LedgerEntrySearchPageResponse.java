package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerEntrySearchPageResponse(
        List<LedgerEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        LedgerEntrySearchSummaryResponse summary
) {
}
