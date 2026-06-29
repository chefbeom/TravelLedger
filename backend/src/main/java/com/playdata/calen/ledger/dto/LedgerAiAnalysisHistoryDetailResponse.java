package com.playdata.calen.ledger.dto;

public record LedgerAiAnalysisHistoryDetailResponse(
        LedgerAiAnalysisHistorySummaryResponse history,
        LedgerAiAnalysisResponse result
) {
}
