package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record LedgerAiAnalysisRequest(
        @NotNull(message = "AI 분석 유형은 필수입니다.")
        LedgerAiAnalysisMode mode,
        LedgerAiAnalysisPeriod periodType,
        LedgerAiComparisonPreset comparisonPreset,
        LocalDate anchorDate,
        LocalDate from,
        LocalDate to,
        LocalDate compareFrom,
        LocalDate compareTo
) {
}