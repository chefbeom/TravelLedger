package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import java.time.LocalDate;
import java.util.List;

/** Calendar decisions and full dataset facts, not HTTP DTOs or minimized provider payloads. */
public final class LedgerAnalysisPreparationData {
    private LedgerAnalysisPreparationData() {}
    public record Draft(LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod periodType, LedgerAiComparisonPreset comparisonPreset,
            LocalDate anchorDate, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {}
    public record DateRange(LocalDate from, LocalDate to) {}
    public record AnalysisPlan(LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod periodType, LedgerAiComparisonPreset comparisonPreset,
            DateRange primaryRange, DateRange comparisonRange) {}
    public record ComparisonRanges(LedgerAiAnalysisPeriod periodType, DateRange primary, DateRange comparison) {}
    public record Dataset(Overview overview, List<CategoryBreakdown> categoryBreakdown, List<PaymentBreakdown> paymentBreakdown,
            List<PeriodComparison> periodComparison, List<ExpenseEntryPayload> expenseEntries, List<ExpenseEntryPayload> topExpenses,
            Overview comparisonOverview, List<CategoryBreakdown> comparisonCategoryBreakdown,
            List<PaymentBreakdown> comparisonPaymentBreakdown, List<ExpenseEntryPayload> comparisonExpenseEntries) {}
}
