package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerLocalReportData.Report;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Canonical analysis results and historical values, not provider requests; JSON stays at codec boundaries. */
public final class LedgerAnalysisHistoryData {
    private LedgerAnalysisHistoryData() {}
    public record Summary(Long id, LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset, LedgerAiAnalysisStatus status, LocalDate from, LocalDate to,
            LocalDate compareFrom, LocalDate compareTo, String model, String title, String summary,
            String errorMessage, LocalDateTime createdAt) {}
    public record StoredHistory(Summary header, String resultJson) {}
    public record Detail(Summary history, AnalysisResult result) {}
    public record HistoryPage(List<Summary> content, int page, int size, long totalElements, int totalPages) {}
    public record Filter(LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod periodType, LocalDate createdFrom,
            LocalDate createdTo, Boolean comparisonOnly, int page, int size) {}
    public record Search(LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod periodType, LocalDateTime createdFrom,
            LocalDateTime createdToExclusive, Boolean comparisonOnly, int page, int size) {}
    /** Comparison preset is intentionally absent: the existing matching SQL does not filter by it. */
    public record MatchKey(Long owner, String provider, String model, LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo,
            LocalDateTime createdAfter) {}
    public record AnalysisResult(
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
        List<LedgerAnalysisData.CategoryBreakdown> categoryBreakdown,
        List<LedgerAnalysisData.PaymentBreakdown> paymentBreakdown,
        List<LedgerAnalysisData.PeriodComparison> periodComparison,
        Report report,
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
    ) {}
}
