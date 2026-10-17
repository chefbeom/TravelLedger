package com.playdata.calen.ledger.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import com.playdata.calen.ledger.domain.model.LedgerLocalReportData.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class LedgerLocalReportPolicyTest {
    final LedgerLocalReportPolicy policy = new LedgerLocalReportPolicy();
    final LocalDate from = LocalDate.of(2026, 6, 1);
    @Test void localReportUsesAll500EntriesRatherThanTheProviderRequestCap() {
        var entry = entry("라프텔", "Group", "Detail", "Card", "12.005", from);
        var entries = Collections.nCopies(500, entry);
        var plan = new AnalysisPlan(false, new DateRange(from, from.plusDays(29)), null);
        var data = new AnalysisDataset(new Overview(from, from.plusDays(29), null, new BigDecimal("6002.500"), null, 500), List.of(), entries, List.of(entry), null, List.of(), List.of());
        var report = policy.buildFallbackReport(plan, data);
        assertThat(report.keySummary()).contains("500건"); assertThat(report.averageAmountInsight()).contains("500건");
        assertThat(policy.buildRecurringExpenseCandidates(entries).get(0).occurrenceCount()).isEqualTo(500);
        assertThat(policy.buildRecurringExpenseCandidates(entries).get(0).totalAmount()).isEqualTo(new BigDecimal("6002.500"));
    }
    @Test void recurringIdentityOmitsGroupRetainsFirstDisplayValuesAndKeepsDateInputOrder() {
        var candidates = policy.buildRecurringExpenseCandidates(List.of(entry(" Repeat ", "First", " D ", "CARD", "12.005", from.plusDays(1)),
                entry("repeat", "Other", "d", " card ", "13.005", from)));
        assertThat(candidates).hasSize(1); var first = candidates.get(0);
        assertThat(first.title()).isEqualTo(" Repeat "); assertThat(first.categoryGroupName()).isEqualTo("First");
        assertThat(first.averageAmount()).isEqualTo(new BigDecimal("12.51")); assertThat(first.firstDate()).isEqualTo(from);
        assertThat(first.dates()).containsExactly(from.plusDays(1), from);
    }
    @Test void recurringTopTenAndRawPaymentGroupingKeepTheirDifferentRules() {
        var entries = new ArrayList<ExpenseEntryPayload>();
        for (int i = 0; i < 12; i++) { var e = entry("Repeat " + i, null, null, i % 2 == 0 ? "Card" : " Card ", Integer.toString(i + 1), from.plusDays(i)); entries.add(e); entries.add(e); }
        var candidates = policy.buildRecurringExpenseCandidates(entries); assertThat(candidates).hasSize(10);
        assertThat(candidates.get(0).title()).isEqualTo("Repeat 11"); assertThat(candidates.get(9).title()).isEqualTo("Repeat 2");
        assertThat(policy.buildExpensePaymentBreakdown(entries)).extracting(PaymentSummaryPayload::paymentMethodName).containsExactly(" Card ", "Card");
    }
    @Test void averageUsesInclusiveDaysHalfUpAndOneDayMinimumWithoutAddingRangeValidation() {
        assertThat(policy.averageDailyExpense(new BigDecimal("1.005"), new DateRange(from, from))).isEqualTo(new BigDecimal("1.01"));
        assertThat(policy.averageDailyExpense(new BigDecimal("1"), new DateRange(from, from.plusDays(2)))).isEqualTo(new BigDecimal("0.33"));
        assertThat(policy.averageDailyExpense(null, new DateRange(from, from.minusDays(1)))).isEqualTo(new BigDecimal("0.00"));
    }
    private ExpenseEntryPayload entry(String title, String group, String detail, String payment, String amount, LocalDate date) {
        return new ExpenseEntryPayload(date, title, null, new BigDecimal(amount), group, detail, payment);
    }
}
