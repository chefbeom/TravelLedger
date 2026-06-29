package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LedgerAiAnalysisResponse(
        Long historyId,
        LedgerAiAnalysisMode mode,
        LedgerAiAnalysisPeriod periodType,
        LedgerAiComparisonPreset comparisonPreset,
        LocalDate from,
        LocalDate to,
        LocalDate compareFrom,
        LocalDate compareTo,
        String model,
        Instant generatedAt,
        BigDecimal totalExpense,
        BigDecimal averageDailyExpense,
        long expenseEntryCount,
        BigDecimal compareTotalExpense,
        List<CategoryBreakdownItemResponse> categoryBreakdown,
        List<PaymentBreakdownItemResponse> paymentBreakdown,
        List<PeriodComparisonItemResponse> periodComparison,
        LedgerAiAnalysisReportResponse report,
        String summary,
        List<String> highlights,
        List<String> warnings,
        List<String> recommendations,
        List<String> categoryInsights,
        List<String> paymentInsights,
        List<String> trendInsights,
        List<String> unusualSpendingInsights,
        List<String> fixedCostInsights,
        String nextPeriodForecast,
        String habitAssessment
) {
    public LedgerAiAnalysisResponse {
        totalExpense = totalExpense == null ? BigDecimal.ZERO : totalExpense;
        averageDailyExpense = averageDailyExpense == null ? BigDecimal.ZERO : averageDailyExpense;
        compareTotalExpense = compareTotalExpense == null ? BigDecimal.ZERO : compareTotalExpense;
        categoryBreakdown = categoryBreakdown == null ? List.of() : List.copyOf(categoryBreakdown);
        paymentBreakdown = paymentBreakdown == null ? List.of() : List.copyOf(paymentBreakdown);
        periodComparison = periodComparison == null ? List.of() : List.copyOf(periodComparison);
        report = report == null ? LedgerAiAnalysisReportResponse.empty() : report;
        summary = summary == null ? "" : summary;
        highlights = safeCopy(highlights);
        warnings = safeCopy(warnings);
        recommendations = safeCopy(recommendations);
        categoryInsights = safeCopy(categoryInsights);
        paymentInsights = safeCopy(paymentInsights);
        trendInsights = safeCopy(trendInsights);
        unusualSpendingInsights = safeCopy(unusualSpendingInsights);
        fixedCostInsights = safeCopy(fixedCostInsights);
        nextPeriodForecast = nextPeriodForecast == null ? "" : nextPeriodForecast;
        habitAssessment = habitAssessment == null ? "" : habitAssessment;
    }

    private static List<String> safeCopy(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }
}