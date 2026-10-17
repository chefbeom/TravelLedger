package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Provider-neutral, minimized analysis data; no account IDs, request IDs, HTTP DTOs or JSON annotations. */
public final class LedgerAnalysisData {
    private LedgerAnalysisData() {}

    public record Request(
            String schemaVersion,
            String language,
            String model,
            String mode,
            String periodType,
            String comparisonPreset,
            LocalDate from,
            LocalDate to,
            LocalDate compareFrom,
            LocalDate compareTo,
            Overview overview,
            List<CategoryBreakdown> categoryBreakdown,
            List<PaymentBreakdown> paymentBreakdown,
            List<PaymentSummaryPayload> expensePaymentBreakdown,
            List<PeriodComparison> periodComparison,
            List<ExpenseEntryPayload> expenseEntries,
            List<ExpenseEntryPayload> topExpenses,
            List<RecurringExpenseCandidatePayload> recurringExpenseCandidates,
            Overview comparisonOverview,
            List<CategoryBreakdown> comparisonCategoryBreakdown,
            List<PaymentBreakdown> comparisonPaymentBreakdown,
            List<ExpenseEntryPayload> comparisonExpenseEntries,
            String focusPrompt,
            PayloadMinimizationSummary payloadMinimization,
            String outputContract
    ) {
    }

    public record PayloadMinimizationSummary(
            int expenseEntryTotalCount,
            int expenseEntrySentCount,
            int expenseEntryOverflowCount,
            int comparisonExpenseEntryTotalCount,
            int comparisonExpenseEntrySentCount,
            int comparisonExpenseEntryOverflowCount,
            int textLimit,
            int memoLimit
    ) {
    }

    public record ExpenseEntryPayload(
            LocalDate entryDate,
            String title,
            String memo,
            BigDecimal amount,
            String categoryGroupName,
            String categoryDetailName,
            String paymentMethodName
    ) {
    }

    public record PaymentSummaryPayload(
            String paymentMethodName,
            BigDecimal totalAmount,
            long entryCount
    ) {
    }

    public record RecurringExpenseCandidatePayload(
            String title,
            String categoryGroupName,
            String categoryDetailName,
            String paymentMethodName,
            int occurrenceCount,
            BigDecimal totalAmount,
            BigDecimal averageAmount,
            LocalDate firstDate,
            LocalDate lastDate,
            List<LocalDate> dates
    ) {
    }

    public record Overview(LocalDate from, LocalDate to, BigDecimal income, BigDecimal expense, BigDecimal balance, long entryCount) {}
    public record CategoryBreakdown(String groupName, String detailName, BigDecimal totalAmount, long entryCount) {}
    public record PaymentBreakdown(String paymentMethodName, PaymentMethodKind kind, BigDecimal totalAmount, long entryCount) {}
    public record PeriodComparison(String label, LocalDate startDate, LocalDate endDate, BigDecimal income, BigDecimal expense, BigDecimal balance) {}

    public record Result(
            Boolean ok,
            String error,
            String summary,
            List<String> highlights,
            List<String> warnings,
            List<String> risks,
            List<String> recommendations,
            List<String> categoryInsights,
            List<String> paymentInsights,
            List<String> trendInsights,
            List<String> unusualSpendingInsights,
            List<String> fixedCostInsights,
            String nextPeriodForecast,
            String habitAssessment,
            Report report
    ) {
    }

    public record Report(
            String keySummary,
            String fullReport,
            String averageAmountInsight,
            List<String> notableSpending,
            List<String> regularSpending,
            List<String> abnormalSpending,
            String topPaymentMethod,
            List<String> subscriptions,
            List<String> fixedExpenses,
            List<String> improvementActions,
            List<String> comparisonFocus
    ) {
        public Report {
            keySummary = keySummary == null ? "" : keySummary;
            fullReport = fullReport == null ? "" : fullReport;
            averageAmountInsight = averageAmountInsight == null ? "" : averageAmountInsight;
            notableSpending = safeCopy(notableSpending);
            regularSpending = safeCopy(regularSpending);
            abnormalSpending = safeCopy(abnormalSpending);
            topPaymentMethod = topPaymentMethod == null ? "" : topPaymentMethod;
            subscriptions = safeCopy(subscriptions);
            fixedExpenses = safeCopy(fixedExpenses);
            improvementActions = safeCopy(improvementActions);
            comparisonFocus = safeCopy(comparisonFocus);
        }

        public static Report empty() {
            return new Report("", "", "", List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of());
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
}
