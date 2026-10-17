package com.playdata.calen.ledger.adapter.support;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import com.playdata.calen.ledger.dto.*;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** Retains the existing API/stored-input records outside calendar and query workflows. */
public final class LedgerAnalysisPreparationMapper {
    private LedgerAnalysisPreparationMapper() {}
    public static Draft draft(LedgerAiAnalysisRequest r) { return new Draft(r.mode(), r.periodType(), r.comparisonPreset(), r.anchorDate(), r.from(), r.to(), r.compareFrom(), r.compareTo()); }
    public static DateRange range(LocalDate from, LocalDate to) { return new DateRange(from, to); }
    public static AnalysisPlan plan(LedgerAiAnalysisMode mode, LedgerAiAnalysisPeriod period, LedgerAiComparisonPreset preset, LocalDate from, LocalDate to, DateRange comparison) {
        return new AnalysisPlan(mode, period, preset, range(from, to), comparison);
    }
    public static OverviewResponse overview(LedgerAnalysisData.Overview v) { return v == null ? null : new OverviewResponse(v.from(), v.to(), v.income(), v.expense(), v.balance(), v.entryCount()); }
    public static List<CategoryBreakdownItemResponse> categories(List<LedgerAnalysisData.CategoryBreakdown> v) { return map(v, r -> new CategoryBreakdownItemResponse(r.groupName(), r.detailName(), r.totalAmount(), r.entryCount())); }
    public static List<PaymentBreakdownItemResponse> payments(List<LedgerAnalysisData.PaymentBreakdown> v) { return map(v, r -> new PaymentBreakdownItemResponse(r.paymentMethodName(), r.kind(), r.totalAmount(), r.entryCount())); }
    public static List<PeriodComparisonItemResponse> periods(List<LedgerAnalysisData.PeriodComparison> v) { return map(v, r -> new PeriodComparisonItemResponse(r.label(), r.startDate(), r.endDate(), r.income(), r.expense(), r.balance())); }
    public static List<LedgerAnalysisPreparedData.ExpenseEntryPayload> expenses(List<LedgerAnalysisData.ExpenseEntryPayload> v) { return map(v, r -> new LedgerAnalysisPreparedData.ExpenseEntryPayload(r.entryDate(), r.title(), r.memo(), r.amount(), r.categoryGroupName(), r.categoryDetailName(), r.paymentMethodName())); }
    public static LedgerAnalysisData.Overview overview(OverviewResponse v) { return v == null ? null : new LedgerAnalysisData.Overview(v.from(), v.to(), v.income(), v.expense(), v.balance(), v.entryCount()); }
    public static List<LedgerAnalysisData.CategoryBreakdown> categoryFacts(List<CategoryBreakdownItemResponse> v) { return map(v, r -> new LedgerAnalysisData.CategoryBreakdown(r.groupName(), r.detailName(), r.totalAmount(), r.entryCount())); }
    public static List<LedgerAnalysisData.PaymentBreakdown> paymentFacts(List<PaymentBreakdownItemResponse> v) { return map(v, r -> new LedgerAnalysisData.PaymentBreakdown(r.paymentMethodName(), r.kind(), r.totalAmount(), r.entryCount())); }
    public static List<LedgerAnalysisData.PeriodComparison> periodFacts(List<PeriodComparisonItemResponse> v) { return map(v, r -> new LedgerAnalysisData.PeriodComparison(r.label(), r.startDate(), r.endDate(), r.income(), r.expense(), r.balance())); }
    private static <S,T> List<T> map(List<S> values, Function<S,T> mapper) { return values == null ? null : values.stream().map(v -> v == null ? null : mapper.apply(v)).toList(); }
}
