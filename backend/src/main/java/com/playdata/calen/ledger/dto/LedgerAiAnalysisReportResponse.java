package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerAiAnalysisReportResponse(
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
    public LedgerAiAnalysisReportResponse {
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

    public static LedgerAiAnalysisReportResponse empty() {
        return new LedgerAiAnalysisReportResponse("", "", "", List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of());
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
