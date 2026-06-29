package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerAiAnalysisHistoryPageResponse(
        List<LedgerAiAnalysisHistorySummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
