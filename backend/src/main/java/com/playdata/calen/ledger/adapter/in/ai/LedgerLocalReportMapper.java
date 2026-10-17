package com.playdata.calen.ledger.adapter.in.ai;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisData;
import com.playdata.calen.ledger.domain.model.LedgerLocalReportData.*;
import com.playdata.calen.ledger.dto.*;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** Maps the full local dataset, never the minimized provider request, without rewriting stored v2 input. */
public final class LedgerLocalReportMapper {
    private LedgerLocalReportMapper() {}
    public static DateRange range(LocalDate from, LocalDate to) { return new DateRange(from, to); }
    public static AnalysisPlan plan(boolean comparison, LocalDate from, LocalDate to, DateRange compare) { return new AnalysisPlan(comparison, range(from, to), compare); }
    public static AnalysisDataset dataset(OverviewResponse overview, List<CategoryBreakdownItemResponse> categories,
            List<LedgerAnalysisPreparedData.ExpenseEntryPayload> expenses, List<LedgerAnalysisPreparedData.ExpenseEntryPayload> top,
            OverviewResponse comparison, List<CategoryBreakdownItemResponse> comparisonCategories,
            List<LedgerAnalysisPreparedData.ExpenseEntryPayload> comparisonExpenses) {
        return new AnalysisDataset(overview(overview), categories(categories), expenses(expenses), expenses(top), overview(comparison), categories(comparisonCategories), expenses(comparisonExpenses));
    }
    public static List<LedgerAnalysisData.ExpenseEntryPayload> expenses(List<LedgerAnalysisPreparedData.ExpenseEntryPayload> values) {
        return map(values, v -> new LedgerAnalysisData.ExpenseEntryPayload(v.entryDate(), v.title(), v.memo(), v.amount(), v.categoryGroupName(), v.categoryDetailName(), v.paymentMethodName()));
    }
    public static List<LedgerAnalysisPreparedData.RecurringExpenseCandidatePayload> recurring(List<LedgerAnalysisData.RecurringExpenseCandidatePayload> values) {
        return map(values, v -> new LedgerAnalysisPreparedData.RecurringExpenseCandidatePayload(v.title(), v.categoryGroupName(), v.categoryDetailName(), v.paymentMethodName(), v.occurrenceCount(), v.totalAmount(), v.averageAmount(), v.firstDate(), v.lastDate(), v.dates()));
    }
    public static List<LedgerAnalysisPreparedData.PaymentSummaryPayload> payments(List<LedgerAnalysisData.PaymentSummaryPayload> values) {
        return map(values, v -> new LedgerAnalysisPreparedData.PaymentSummaryPayload(v.paymentMethodName(), v.totalAmount(), v.entryCount()));
    }
    public static LedgerAiAnalysisReportResponse response(Report r) {
        return new LedgerAiAnalysisReportResponse(r.keySummary(), r.fullReport(), r.averageAmountInsight(), r.notableSpending(), r.regularSpending(), r.abnormalSpending(), r.topPaymentMethod(), r.subscriptions(), r.fixedExpenses(), r.improvementActions(), r.comparisonFocus());
    }
    private static LedgerAnalysisData.Overview overview(OverviewResponse v) { return v == null ? null : new LedgerAnalysisData.Overview(v.from(), v.to(), v.income(), v.expense(), v.balance(), v.entryCount()); }
    private static List<LedgerAnalysisData.CategoryBreakdown> categories(List<CategoryBreakdownItemResponse> values) { return map(values, v -> new LedgerAnalysisData.CategoryBreakdown(v.groupName(), v.detailName(), v.totalAmount(), v.entryCount())); }
    private static <S,T> List<T> map(List<S> values, Function<S,T> mapper) { return values == null ? null : values.stream().map(v -> v == null ? null : mapper.apply(v)).toList(); }
}
