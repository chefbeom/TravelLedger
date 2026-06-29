package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LedgerAiAnalysisHistorySummaryResponse(
        Long id,
        LedgerAiAnalysisMode mode,
        LedgerAiAnalysisPeriod periodType,
        LedgerAiComparisonPreset comparisonPreset,
        LedgerAiAnalysisStatus status,
        LocalDate from,
        LocalDate to,
        LocalDate compareFrom,
        LocalDate compareTo,
        String model,
        String title,
        String summary,
        String errorMessage,
        LocalDateTime createdAt
) {
}
