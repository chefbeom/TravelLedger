package com.playdata.calen.ledger.adapter.support;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.domain.model.LedgerLocalReportData.Report;
import com.playdata.calen.ledger.dto.*;

/** Exact historical HTTP mapping; no provider minimization or new normalization. */
public final class LedgerAnalysisHistoryMapper {
    private LedgerAnalysisHistoryMapper() {}
    public static Summary summary(LedgerAiAnalysisHistory history) {
        return new Summary(
                history.getId(),
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getStatus(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                history.getModel(),
                history.getTitle(),
                history.getSummary(),
                history.getErrorMessage(),
                history.getCreatedAt()
        );
    }
    public static StoredHistory stored(LedgerAiAnalysisHistory history) {
        return history == null ? null : new StoredHistory(summary(history), history.getResultJson());
    }
    public static LedgerAiAnalysisHistorySummaryResponse response(Summary v) {
        return v == null ? null : new LedgerAiAnalysisHistorySummaryResponse(v.id(), v.mode(), v.periodType(), v.comparisonPreset(), v.status(), v.from(), v.to(), v.compareFrom(), v.compareTo(), v.model(), v.title(), v.summary(), v.errorMessage(), v.createdAt());
    }
    public static LedgerAiAnalysisHistoryDetailResponse response(Detail v) {
        return v == null ? null : new LedgerAiAnalysisHistoryDetailResponse(response(v.history()), response(v.result()));
    }
    public static LedgerAiAnalysisHistoryPageResponse response(HistoryPage v) {
        return new LedgerAiAnalysisHistoryPageResponse(v.content().stream().map(LedgerAnalysisHistoryMapper::response).toList(), v.page(), v.size(), v.totalElements(), v.totalPages());
    }
    public static AnalysisResult facts(LedgerAiAnalysisResponse v) {
        return v == null ? null : new AnalysisResult(
                v.historyId(),
                v.mode(),
                v.periodType(),
                v.comparisonPreset(),
                v.from(),
                v.to(),
                v.compareFrom(),
                v.compareTo(),
                v.model(),
                v.generatedAt(),
                v.totalExpense(),
                v.averageDailyExpense(),
                v.expenseEntryCount(),
                v.compareTotalExpense(),
                LedgerAnalysisPreparationMapper.categoryFacts(v.categoryBreakdown()),
                LedgerAnalysisPreparationMapper.paymentFacts(v.paymentBreakdown()),
                LedgerAnalysisPreparationMapper.periodFacts(v.periodComparison()),
                reportData(v.report()),
                v.summary(),
                v.highlights(),
                v.warnings(),
                v.recommendations(),
                v.categoryInsights(),
                v.paymentInsights(),
                v.trendInsights(),
                v.unusualSpendingInsights(),
                v.fixedCostInsights(),
                v.nextPeriodForecast(),
                v.habitAssessment());
    }
    public static LedgerAiAnalysisResponse response(AnalysisResult v) {
        return v == null ? null : new LedgerAiAnalysisResponse(
                v.historyId(),
                v.mode(),
                v.periodType(),
                v.comparisonPreset(),
                v.from(),
                v.to(),
                v.compareFrom(),
                v.compareTo(),
                v.model(),
                v.generatedAt(),
                v.totalExpense(),
                v.averageDailyExpense(),
                v.expenseEntryCount(),
                v.compareTotalExpense(),
                LedgerAnalysisPreparationMapper.categories(v.categoryBreakdown()),
                LedgerAnalysisPreparationMapper.payments(v.paymentBreakdown()),
                LedgerAnalysisPreparationMapper.periods(v.periodComparison()),
                reportResponse(v.report()),
                v.summary(),
                v.highlights(),
                v.warnings(),
                v.recommendations(),
                v.categoryInsights(),
                v.paymentInsights(),
                v.trendInsights(),
                v.unusualSpendingInsights(),
                v.fixedCostInsights(),
                v.nextPeriodForecast(),
                v.habitAssessment());
    }
    private static Report reportData(LedgerAiAnalysisReportResponse v) {
        return v == null ? null : new Report(v.keySummary(), v.fullReport(), v.averageAmountInsight(), v.notableSpending(), v.regularSpending(), v.abnormalSpending(), v.topPaymentMethod(), v.subscriptions(), v.fixedExpenses(), v.improvementActions(), v.comparisonFocus());
    }
    private static LedgerAiAnalysisReportResponse reportResponse(Report v) {
        return v == null ? null : new LedgerAiAnalysisReportResponse(v.keySummary(), v.fullReport(), v.averageAmountInsight(), v.notableSpending(), v.regularSpending(), v.abnormalSpending(), v.topPaymentMethod(), v.subscriptions(), v.fixedExpenses(), v.improvementActions(), v.comparisonFocus());
    }
}
