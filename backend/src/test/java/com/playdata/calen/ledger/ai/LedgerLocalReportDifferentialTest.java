package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerLocalReportMapper;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy;
import com.playdata.calen.ledger.dto.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class LedgerLocalReportDifferentialTest {
    final LedgerLocalReportLegacyOracle old = new LedgerLocalReportLegacyOracle();
    final LedgerLocalReportPolicy modern = new LedgerLocalReportPolicy();
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void completeLocalReportAndPreparedGroupingJsonMatchAcross128FullDatasets() throws Exception {
        for (int seed = 0; seed < 128; seed++) {
            var from = LocalDate.of(2026, 6, 1); var to = seed % 11 == 0 ? from.minusDays(1) : from.plusDays(seed % 30);
            var entries = expenses(seed, from); var comparisonEntries = (seed & 4) == 0 ? List.<ExpenseEntryPayload>of() : expenses(seed + 1, from.minusMonths(1));
            var categories = categories(seed); var comparisonCategories = categories(seed + 2);
            BigDecimal total = seed % 5 == 0 ? null : seed % 5 == 1 ? BigDecimal.ZERO : entries.stream().map(ExpenseEntryPayload::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var overview = new OverviewResponse(from, to, BigDecimal.ZERO, total, total == null ? null : total.negate(), entries.size());
            var comparisonOverview = (seed & 2) == 0 ? null : new OverviewResponse(from.minusMonths(1), from.minusDays(1), null,
                    seed % 3 == 0 ? BigDecimal.ZERO : seed % 3 == 1 ? new BigDecimal("-50.005") : new BigDecimal("12345.005"), null, comparisonEntries.size());
            var top = entries.stream().limit(12).toList();
            var plan = new LedgerLocalReportLegacyOracle.AnalysisPlan((seed & 1) == 0 ? LedgerAiAnalysisMode.PERIOD : LedgerAiAnalysisMode.COMPARISON,
                    LedgerAiAnalysisPeriod.MONTH, LedgerAiComparisonPreset.CURRENT_MONTH_VS_PREVIOUS_MONTH,
                    new LedgerLocalReportLegacyOracle.DateRange(from, to), new LedgerLocalReportLegacyOracle.DateRange(from.minusMonths(1), from.minusDays(1)), "raw focus");
            var dataset = new LedgerLocalReportLegacyOracle.AnalysisDataset(overview, categories, List.of(), List.of(), entries, top,
                    comparisonOverview, comparisonCategories, List.of(), comparisonEntries);
            var expected = old.buildFallbackReport(plan, dataset);
            var actual = modern.buildFallbackReport(LedgerLocalReportMapper.plan(plan.mode() == LedgerAiAnalysisMode.COMPARISON, from, to,
                    LedgerLocalReportMapper.range(from.minusMonths(1), from.minusDays(1))), LedgerLocalReportMapper.dataset(overview, categories, entries, top, comparisonOverview, comparisonCategories, comparisonEntries));
            equal(expected, LedgerLocalReportMapper.response(actual));
            equal(old.buildRecurringExpenseCandidates(entries), LedgerLocalReportMapper.recurring(modern.buildRecurringExpenseCandidates(LedgerLocalReportMapper.expenses(entries))));
            equal(old.buildExpensePaymentBreakdown(entries), LedgerLocalReportMapper.payments(modern.buildExpensePaymentBreakdown(LedgerLocalReportMapper.expenses(entries))));
            assertThat(modern.averageDailyExpense(total, LedgerLocalReportMapper.range(from, to))).isEqualTo(old.averageDailyExpense(total, plan.primaryRange()));
        }
    }
    @Test void sharedGroupingKeepsRawPaymentNamesButNormalizesRecurringKeysAndRejectsNullAmountsTheSameWay() throws Exception {
        var date = LocalDate.of(2026, 6, 1);
        var entries = Arrays.asList(new ExpenseEntryPayload(date, " Repeat ", null, new BigDecimal("12.005"), "First group", " D ", "CARD"),
                new ExpenseEntryPayload(date.plusDays(1), "repeat", "memo", new BigDecimal("13.005"), "Other group", "d", " card "),
                new ExpenseEntryPayload(date, "Other", null, BigDecimal.ZERO, null, null, " \t "));
        equal(old.buildRecurringExpenseCandidates(entries), LedgerLocalReportMapper.recurring(modern.buildRecurringExpenseCandidates(LedgerLocalReportMapper.expenses(entries))));
        var withNull = List.of(new ExpenseEntryPayload(date, "Title", null, null, null, null, null));
        equal(old.buildExpensePaymentBreakdown(withNull), LedgerLocalReportMapper.payments(modern.buildExpensePaymentBreakdown(LedgerLocalReportMapper.expenses(withNull))));
        assertThatThrownBy(() -> old.buildRecurringExpenseCandidates(withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> modern.buildRecurringExpenseCandidates(LedgerLocalReportMapper.expenses(withNull))).isInstanceOf(NullPointerException.class);
    }
    private List<ExpenseEntryPayload> expenses(int seed, LocalDate from) {
        if (seed % 13 == 0) return List.of();
        String[] titles = {"라프텔 구독", "보험", "게임 충전", "휴대폰 요금", "커피", "ordinary", null, " "};
        String[] payments = {null, "", " \t ", " CARD ", "card", "Cash", "CARD"};
        var entries = new ArrayList<ExpenseEntryPayload>(); int count = seed % 17 == 0 ? 500 : 24 + seed % 40;
        for (int i = 0; i < count; i++) {
            var title = titles[(i + seed) % titles.length];
            entries.add(new ExpenseEntryPayload(from.plusDays(i % 28), title, i % 2 == 0 ? null : " raw memo ",
                    i % 11 == 0 ? new BigDecimal("100000.005") : new BigDecimal("12.005").multiply(BigDecimal.valueOf(i % 5 + 1)),
                    i % 2 == 0 ? "Group" : null, i % 3 == 0 ? null : "Detail", payments[(i + seed) % payments.length]));
        }
        return entries;
    }
    private List<CategoryBreakdownItemResponse> categories(int seed) {
        return List.of(new CategoryBreakdownItemResponse(null, null, seed % 2 == 0 ? null : new BigDecimal("100.005"), 5),
                new CategoryBreakdownItemResponse("Food", "Meal", new BigDecimal("200.005"), 10),
                new CategoryBreakdownItemResponse("Food", "Meal", new BigDecimal(seed % 3 == 0 ? "50.004" : "300.004"), 10),
                new CategoryBreakdownItemResponse(" Group ", " \t ", new BigDecimal("10.005"), 1));
    }
    private void equal(Object expected, Object actual) throws Exception { assertThat(json.writeValueAsString(actual)).isEqualTo(json.writeValueAsString(expected)); }
}
