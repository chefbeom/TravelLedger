package com.playdata.calen.ledger.ai;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerAiAnalysisPayloadBuilder {

    private static final int PROVIDER_TEXT_LIMIT = 80;
    private static final int PROVIDER_MEMO_LIMIT = 160;

    private final LedgerAiAnalysisTextSanitizer aiText;

    public List<LedgerAiAnalysisService.ExpenseEntryPayload> providerExpenseEntries(
            List<LedgerAiAnalysisService.ExpenseEntryPayload> entries,
            int limit
    ) {
        return entries.stream()
                .limit(limit)
                .map(this::sanitizeProviderExpenseEntry)
                .toList();
    }

    public List<LedgerAiAnalysisService.RecurringExpenseCandidatePayload> providerRecurringCandidates(
            List<LedgerAiAnalysisService.RecurringExpenseCandidatePayload> candidates
    ) {
        return candidates.stream()
                .map(candidate -> new LedgerAiAnalysisService.RecurringExpenseCandidatePayload(
                        aiText.limitText(candidate.title(), PROVIDER_TEXT_LIMIT),
                        aiText.limitText(candidate.categoryGroupName(), PROVIDER_TEXT_LIMIT),
                        aiText.limitText(candidate.categoryDetailName(), PROVIDER_TEXT_LIMIT),
                        aiText.limitText(candidate.paymentMethodName(), PROVIDER_TEXT_LIMIT),
                        candidate.occurrenceCount(),
                        candidate.totalAmount(),
                        candidate.averageAmount(),
                        candidate.firstDate(),
                        candidate.lastDate(),
                        candidate.dates()
                ))
                .toList();
    }

    public LedgerAiAnalysisService.PayloadMinimizationSummary buildPayloadMinimizationSummary(
            int expenseTotal,
            int comparisonTotal,
            List<LedgerAiAnalysisService.ExpenseEntryPayload> providerExpenseEntries,
            List<LedgerAiAnalysisService.ExpenseEntryPayload> providerComparisonExpenseEntries
    ) {
        return new LedgerAiAnalysisService.PayloadMinimizationSummary(
                expenseTotal,
                providerExpenseEntries.size(),
                Math.max(0, expenseTotal - providerExpenseEntries.size()),
                comparisonTotal,
                providerComparisonExpenseEntries.size(),
                Math.max(0, comparisonTotal - providerComparisonExpenseEntries.size()),
                PROVIDER_TEXT_LIMIT,
                PROVIDER_MEMO_LIMIT
        );
    }

    private LedgerAiAnalysisService.ExpenseEntryPayload sanitizeProviderExpenseEntry(
            LedgerAiAnalysisService.ExpenseEntryPayload entry
    ) {
        return new LedgerAiAnalysisService.ExpenseEntryPayload(
                entry.entryDate(),
                aiText.limitText(entry.title(), PROVIDER_TEXT_LIMIT),
                aiText.limitText(entry.memo(), PROVIDER_MEMO_LIMIT),
                entry.amount(),
                aiText.limitText(entry.categoryGroupName(), PROVIDER_TEXT_LIMIT),
                aiText.limitText(entry.categoryDetailName(), PROVIDER_TEXT_LIMIT),
                aiText.limitText(entry.paymentMethodName(), PROVIDER_TEXT_LIMIT)
        );
    }
}