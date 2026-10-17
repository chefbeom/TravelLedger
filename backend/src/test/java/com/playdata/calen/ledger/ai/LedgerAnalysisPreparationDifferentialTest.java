package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisDatasetPreparation;
import com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisDatasetQueryAdapter;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.service.StatisticsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class LedgerAnalysisPreparationDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void calendarDefaultsBoundariesValidationAndFocusNormalizationMatchTheFrozenResolverAcross1764Requests() throws Exception {
        var oracle = new LedgerAnalysisPreparationLegacyOracle(mock(StatisticsService.class), mock(LedgerEntryRepository.class));
        var text = new LedgerAiAnalysisTextSanitizer();
        var modes = new ArrayList<LedgerAiAnalysisMode>(Arrays.asList(LedgerAiAnalysisMode.values())); modes.add(null);
        var periods = new ArrayList<LedgerAiAnalysisPeriod>(Arrays.asList(LedgerAiAnalysisPeriod.values())); periods.add(null);
        var presets = new ArrayList<LedgerAiComparisonPreset>(Arrays.asList(LedgerAiComparisonPreset.values())); presets.add(null);
        var anchors = List.of(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 6, 30),
                LocalDate.of(2024, 7, 1), LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 28), LocalDate.of(2026, 1, 4),
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 10, 17), LocalDate.of(2026, 12, 31));
        int count = 0;
        for (var anchor : anchors) for (var mode : modes) for (var period : periods) for (var preset : presets) {
            int seed = count++; LocalDate from = anchor.minusDays(5), to = anchor.plusDays(2), compareFrom = anchor.minusDays(20), compareTo = anchor.minusDays(10);
            if (seed % 11 == 0) from = null; else if (seed % 13 == 0) to = anchor.minusDays(6); else if (seed % 17 == 0) to = from.plusDays(366);
            if (seed % 19 == 0) compareFrom = null;
            var request = new LedgerAiAnalysisRequest(mode, period, preset, anchor, from, to, compareFrom, compareTo, " focus https://example.invalid/path token ", "client-1");
            LedgerAnalysisPreparationLegacyOracle.AnalysisPlan expected = null; RuntimeException failure = null;
            try { expected = oracle.resolvePlan(request); } catch (RuntimeException error) { failure = error; }
            if (failure != null) {
                var captured = failure;
                assertThatThrownBy(() -> LedgerAnalysisCalendarPolicy.resolvePlan(LedgerAnalysisPreparationMapper.draft(request), () -> anchor)).isExactlyInstanceOf(captured.getClass()).hasMessage(captured.getMessage());
            } else {
                var actual = LedgerAnalysisCalendarPolicy.resolvePlan(LedgerAnalysisPreparationMapper.draft(request), () -> { throw new AssertionError("explicit anchor must not read the clock"); });
                var compare = actual.comparisonRange();
                equal(expected, new LedgerAnalysisPreparationLegacyOracle.AnalysisPlan(actual.mode(), actual.periodType(), actual.comparisonPreset(),
                        new LedgerAnalysisPreparationLegacyOracle.DateRange(actual.primaryRange().from(), actual.primaryRange().to()), compare == null ? null : new LedgerAnalysisPreparationLegacyOracle.DateRange(compare.from(), compare.to()), text.redactSensitiveText(request.focusPrompt(), 500)));
            }
        }
        assertThat(count).isEqualTo(1764);
    }
    @Test void completeDatasetJsonNormalizationQueryOrderAndWeeklyTopSuppressionMatchAcross64Plans() throws Exception {
        for (int seed = 0; seed < 64; seed++) {
            var statistics = mock(StatisticsService.class); var entries = mock(LedgerEntryRepository.class); var trace = new ArrayList<String>();
            var primary = new LedgerAnalysisPreparationLegacyOracle.DateRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 11));
            var comparison = seed % 2 == 0 ? null : new LedgerAnalysisPreparationLegacyOracle.DateRange(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4));
            var plan = new LedgerAnalysisPreparationLegacyOracle.AnalysisPlan(seed % 2 == 0 ? LedgerAiAnalysisMode.PERIOD : LedgerAiAnalysisMode.COMPARISON,
                    LedgerAiAnalysisPeriod.values()[seed % 6], seed % 3 == 0 ? LedgerAiComparisonPreset.WEEKLY_DIGEST : null, primary, comparison, "focus");
            var category = List.of(new CategoryBreakdownItemResponse(" Group ", null, new BigDecimal("1.2345"), 2));
            var payment = List.of(new PaymentBreakdownItemResponse(null, null, null, 3));
            when(statistics.getOverview(anyLong(), any(), any())).thenAnswer(c -> { trace.add("overview:" + c.getArgument(0) + ":" + c.getArgument(1)); return new OverviewResponse(c.getArgument(1), c.getArgument(2), null, new BigDecimal("1.2345"), null, 2); });
            when(statistics.getCategoryBreakdown(anyLong(), any(), any(), any())).thenAnswer(c -> { trace.add("categories:" + c.getArgument(0) + ":" + c.getArgument(1) + ":" + c.getArgument(3)); return category; });
            when(statistics.getPaymentBreakdown(anyLong(), any(), any())).thenAnswer(c -> { trace.add("payments:" + c.getArgument(0) + ":" + c.getArgument(1)); return payment; });
            when(statistics.compare(anyLong(), any(), any(), anyInt())).thenAnswer(c -> { trace.add("periods:" + c.getArgument(0) + ":" + c.getArgument(1) + ":" + c.getArgument(2) + ":" + c.getArgument(3)); return List.of(new PeriodComparisonItemResponse(" raw ", primary.from(), primary.to(), null, new BigDecimal("9.005"), null)); });
            var row = mock(LedgerEntryRepository.AiExpenseEntryAggregate.class); when(row.getEntryDate()).thenReturn(primary.from()); when(row.getTitle()).thenReturn(seed % 2 == 0 ? null : " Title ");
            when(row.getMemo()).thenReturn(seed % 3 == 0 ? null : " memo "); when(row.getAmount()).thenReturn(seed % 4 == 0 ? null : new BigDecimal("12.3456"));
            when(row.getCategoryGroupName()).thenReturn(seed % 2 == 0 ? null : " Group "); when(row.getCategoryDetailName()).thenReturn(seed % 2 == 0 ? "\u2003" : " Detail ");
            when(row.getPaymentMethodName()).thenReturn(seed % 3 == 0 ? null : " Card ");
            when(entries.findExpenseEntriesForAiAnalysis(anyLong(), any(), any(), any())).thenAnswer(c -> { trace.add("expenses:" + c.getArgument(0) + ":" + c.getArgument(1) + ":" + c.getArgument(3)); return List.of(row, row); });
            when(entries.findTopExpenseEntriesForAiAnalysis(anyLong(), any(), any(), any(), any())).thenAnswer(c -> { Pageable p = c.getArgument(4); trace.add("top:" + c.getArgument(0) + ":" + c.getArgument(1) + ":" + p.getPageNumber() + ":" + p.getPageSize()); return List.of(row); });
            var oracle = new LedgerAnalysisPreparationLegacyOracle(statistics, entries); var expected = oracle.buildDataset(7L, plan); var expectedTrace = List.copyOf(trace); trace.clear();
            var modern = new LedgerAnalysisDatasetPreparation(new LedgerAnalysisDatasetQueryAdapter(statistics, entries));
            var actual = modern.prepare(7L, LedgerAnalysisPreparationMapper.plan(plan.mode(), plan.periodType(), plan.comparisonPreset(), primary.from(), primary.to(), comparison == null ? null : LedgerAnalysisPreparationMapper.range(comparison.from(), comparison.to())));
            equal(expected, actual); assertThat(trace).containsExactlyElementsOf(expectedTrace);
            equal(expected, new LedgerAnalysisPreparationLegacyOracle.AnalysisDataset(LedgerAnalysisPreparationMapper.overview(actual.overview()), LedgerAnalysisPreparationMapper.categories(actual.categoryBreakdown()),
                    LedgerAnalysisPreparationMapper.payments(actual.paymentBreakdown()), LedgerAnalysisPreparationMapper.periods(actual.periodComparison()), LedgerAnalysisPreparationMapper.expenses(actual.expenseEntries()),
                    LedgerAnalysisPreparationMapper.expenses(actual.topExpenses()), LedgerAnalysisPreparationMapper.overview(actual.comparisonOverview()), LedgerAnalysisPreparationMapper.categories(actual.comparisonCategoryBreakdown()),
                    LedgerAnalysisPreparationMapper.payments(actual.comparisonPaymentBreakdown()), LedgerAnalysisPreparationMapper.expenses(actual.comparisonExpenseEntries())));
        }
    }
    private void equal(Object expected, Object actual) throws Exception { assertThat(json.writeValueAsString(actual)).isEqualTo(json.writeValueAsString(expected)); }
}
