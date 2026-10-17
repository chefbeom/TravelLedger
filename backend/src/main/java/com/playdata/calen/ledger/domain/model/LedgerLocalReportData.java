package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import java.time.LocalDate;
import java.util.List;

/** Full caller dataset for local reporting, deliberately distinct from capped provider requests. */
public final class LedgerLocalReportData {
    private LedgerLocalReportData() {}
    public record DateRange(LocalDate from, LocalDate to) {}
    public record AnalysisPlan(boolean comparison, DateRange primaryRange, DateRange comparisonRange) {}
    public record AnalysisDataset(Overview overview, List<CategoryBreakdown> categoryBreakdown,
            List<ExpenseEntryPayload> expenseEntries, List<ExpenseEntryPayload> topExpenses, Overview comparisonOverview,
            List<CategoryBreakdown> comparisonCategoryBreakdown, List<ExpenseEntryPayload> comparisonExpenseEntries) {}
    /** No provider-response normalization is applied to local report copy. */
    public record Report(String keySummary, String fullReport, String averageAmountInsight, List<String> notableSpending,
            List<String> regularSpending, List<String> abnormalSpending, String topPaymentMethod, List<String> subscriptions,
            List<String> fixedExpenses, List<String> improvementActions, List<String> comparisonFocus) {}
}
