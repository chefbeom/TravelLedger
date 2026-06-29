package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerAiAnalysisPayloadBuilderTest {

    private final LedgerAiAnalysisPayloadBuilder builder = new LedgerAiAnalysisPayloadBuilder(
            new LedgerAiAnalysisTextSanitizer()
    );

    @Test
    void limitsProviderEntriesAndReportsOverflowCounts() {
        String longTitle = "T".repeat(120);
        String longMemo = "M".repeat(220);
        List<LedgerAiAnalysisService.ExpenseEntryPayload> entries = new ArrayList<>();
        entries.add(entry(longTitle, longMemo));
        entries.add(entry("Coffee", "Morning"));
        entries.add(entry("Lunch", "Team"));

        List<LedgerAiAnalysisService.ExpenseEntryPayload> providerEntries = builder.providerExpenseEntries(entries, 2);
        LedgerAiAnalysisService.PayloadMinimizationSummary summary = builder.buildPayloadMinimizationSummary(
                entries.size(),
                0,
                providerEntries,
                List.of()
        );

        assertThat(providerEntries).hasSize(2);
        assertThat(providerEntries.get(0).title()).hasSize(80);
        assertThat(providerEntries.get(0).memo()).hasSize(160);
        assertThat(summary.expenseEntryTotalCount()).isEqualTo(3);
        assertThat(summary.expenseEntrySentCount()).isEqualTo(2);
        assertThat(summary.expenseEntryOverflowCount()).isEqualTo(1);
        assertThat(summary.textLimit()).isEqualTo(80);
        assertThat(summary.memoLimit()).isEqualTo(160);
    }

    @Test
    void limitsRecurringCandidateTextWithoutChangingCountsOrDates() {
        LedgerAiAnalysisService.RecurringExpenseCandidatePayload candidate = new LedgerAiAnalysisService.RecurringExpenseCandidatePayload(
                "S".repeat(120),
                "생활비".repeat(40),
                "구독".repeat(50),
                "Card".repeat(40),
                3,
                BigDecimal.valueOf(30000),
                BigDecimal.valueOf(10000),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 21),
                List.of(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 21))
        );

        List<LedgerAiAnalysisService.RecurringExpenseCandidatePayload> providerCandidates = builder.providerRecurringCandidates(List.of(candidate));

        assertThat(providerCandidates).hasSize(1);
        LedgerAiAnalysisService.RecurringExpenseCandidatePayload result = providerCandidates.get(0);
        assertThat(result.title()).hasSize(80);
        assertThat(result.categoryGroupName()).hasSize(80);
        assertThat(result.categoryDetailName()).hasSize(80);
        assertThat(result.paymentMethodName()).hasSize(80);
        assertThat(result.occurrenceCount()).isEqualTo(3);
        assertThat(result.dates()).containsExactlyElementsOf(candidate.dates());
    }

    private LedgerAiAnalysisService.ExpenseEntryPayload entry(String title, String memo) {
        return new LedgerAiAnalysisService.ExpenseEntryPayload(
                LocalDate.of(2026, 6, 1),
                title,
                memo,
                BigDecimal.valueOf(1000),
                "Food",
                "Cafe",
                "Card"
        );
    }
}