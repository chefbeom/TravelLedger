package com.playdata.calen.ledger.adapter.in.ai;

import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.LedgerAiN8nPayload;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import java.util.List;
import java.util.function.Function;

/** Keeps persisted v2 input JSON and legacy API DTOs outside the outbound analysis port. */
public final class LedgerAnalysisBoundaryMapper {
    private LedgerAnalysisBoundaryMapper() {}

    public static Request toRequest(LedgerAiN8nPayload value) {
        if (value == null) return null;
        return new Request(value.schemaVersion(),value.language(),value.model(),code(value.mode()),code(value.periodType()),
                code(value.comparisonPreset()),value.from(),value.to(),value.compareFrom(),value.compareTo(),
                overview(value.overview()),map(value.categoryBreakdown(),item -> new CategoryBreakdown(item.groupName(),item.detailName(),item.totalAmount(),item.entryCount())),
                payments(value.paymentBreakdown()),map(value.expensePaymentBreakdown(),item -> new PaymentSummaryPayload(item.paymentMethodName(),item.totalAmount(),item.entryCount())),
                map(value.periodComparison(),item -> new PeriodComparison(item.label(),item.startDate(),item.endDate(),item.income(),item.expense(),item.balance())),
                expenses(value.expenseEntries()),expenses(value.topExpenses()),
                map(value.recurringExpenseCandidates(),item -> new RecurringExpenseCandidatePayload(item.title(),item.categoryGroupName(),item.categoryDetailName(),
                        item.paymentMethodName(),item.occurrenceCount(),item.totalAmount(),item.averageAmount(),item.firstDate(),item.lastDate(),item.dates())),
                overview(value.comparisonOverview()),map(value.comparisonCategoryBreakdown(),item -> new CategoryBreakdown(item.groupName(),item.detailName(),item.totalAmount(),item.entryCount())),
                payments(value.comparisonPaymentBreakdown()),expenses(value.comparisonExpenseEntries()),value.focusPrompt(),
                value.payloadMinimization() == null ? null : new PayloadMinimizationSummary(value.payloadMinimization().expenseEntryTotalCount(),
                        value.payloadMinimization().expenseEntrySentCount(),value.payloadMinimization().expenseEntryOverflowCount(),
                        value.payloadMinimization().comparisonExpenseEntryTotalCount(),value.payloadMinimization().comparisonExpenseEntrySentCount(),
                        value.payloadMinimization().comparisonExpenseEntryOverflowCount(),value.payloadMinimization().textLimit(),value.payloadMinimization().memoLimit()),
                value.outputContract());
    }

    private static String code(Enum<?> value) { return value == null ? null : value.name(); }
    private static Overview overview(com.playdata.calen.ledger.dto.OverviewResponse value) {
        return value == null ? null : new Overview(value.from(),value.to(),value.income(),value.expense(),value.balance(),value.entryCount());
    }
    private static List<PaymentBreakdown> payments(List<com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse> values) {
        return map(values,item -> new PaymentBreakdown(item.paymentMethodName(),item.kind(),item.totalAmount(),item.entryCount()));
    }
    private static List<ExpenseEntryPayload> expenses(List<com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.ExpenseEntryPayload> values) {
        return map(values,item -> new ExpenseEntryPayload(item.entryDate(),item.title(),item.memo(),item.amount(),item.categoryGroupName(),item.categoryDetailName(),item.paymentMethodName()));
    }
    private static <T,R> List<R> map(List<T> values,Function<T,R> mapper) {
        return values == null ? null : values.stream().map(value -> value == null ? null : mapper.apply(value)).toList();
    }
}
