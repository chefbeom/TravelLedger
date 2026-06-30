package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
        LocalDate compareTo,
        @Size(max = 80, message = "AI analysis clientRequestId must be at most 80 characters.")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$", message = "AI analysis clientRequestId may contain only letters, numbers, dot, underscore, colon, and hyphen.")
        String clientRequestId
) {
}