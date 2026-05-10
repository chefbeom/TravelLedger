package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerEntryChangeHistoryPageResponse(
        List<LedgerEntryChangeHistorySummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
