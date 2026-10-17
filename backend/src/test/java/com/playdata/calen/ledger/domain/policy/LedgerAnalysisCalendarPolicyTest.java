package com.playdata.calen.ledger.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LedgerAnalysisCalendarPolicyTest {
    @Test void clockIsLazyAfterModeValidationAndReadOnlyOnceWhenAnchorIsMissing() {
        var calls = new AtomicInteger(); var today = LocalDate.of(2024, 2, 29);
        var draft = new Draft(null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> LedgerAnalysisCalendarPolicy.resolvePlan(draft, () -> { calls.incrementAndGet(); return today; })).isInstanceOf(com.playdata.calen.common.exception.BadRequestException.class);
        assertThat(calls).hasValue(0);
        var plan = LedgerAnalysisCalendarPolicy.resolvePlan(new Draft(LedgerAiAnalysisMode.PERIOD, null, null, null, null, null, null, null), () -> { calls.incrementAndGet(); return today; });
        assertThat(calls).hasValue(1); assertThat(plan.primaryRange()).isEqualTo(new DateRange(LocalDate.of(2024, 2, 1), today));
        LedgerAnalysisCalendarPolicy.resolvePlan(new Draft(LedgerAiAnalysisMode.PERIOD, null, null, today, null, null, null, null), () -> { throw new AssertionError("clock must not be used"); });
    }
    @Test void inclusive366DayLimitAndValidationOrderStayUnchanged() {
        var from = LocalDate.of(2024, 1, 1);
        assertThat(LedgerAnalysisCalendarPolicy.validateCustomRange(from, from.plusDays(365), "custom")).isEqualTo(new DateRange(from, from.plusDays(365)));
        assertThatThrownBy(() -> LedgerAnalysisCalendarPolicy.validateCustomRange(from, from.plusDays(366), "custom")).hasMessage("custom must be within 1 year.");
        assertThatThrownBy(() -> LedgerAnalysisCalendarPolicy.validateCustomRange(null, from, "primary comparison period")).hasMessage("primary comparison period must be within 1 year.");
    }
    @Test void weekAndHalfYearBoundariesAndHistoryWindowsKeepTheirDistinctSemantics() {
        assertThat(LedgerAnalysisCalendarPolicy.resolvePeriodRange(LedgerAiAnalysisPeriod.WEEK, LocalDate.of(2026, 1, 4), null, null)).isEqualTo(new DateRange(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)));
        assertThat(LedgerAnalysisCalendarPolicy.resolvePeriodRange(LedgerAiAnalysisPeriod.HALF_YEAR, LocalDate.of(2026, 7, 1), null, null)).isEqualTo(new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31)));
        assertThat(LedgerAnalysisCalendarPolicy.comparisonUnitFor(LedgerAiAnalysisPeriod.HALF_YEAR)).isEqualTo(ComparisonUnit.MONTH);
        assertThat(LedgerAnalysisCalendarPolicy.comparisonPeriodsFor(LedgerAiAnalysisPeriod.HALF_YEAR)).isEqualTo(6);
        var plan = LedgerAnalysisCalendarPolicy.resolvePlan(new Draft(LedgerAiAnalysisMode.COMPARISON, null, LedgerAiComparisonPreset.WEEKLY_DIGEST, LocalDate.of(2026, 1, 5), null, null, null, null), null);
        assertThat(plan.primaryRange()).isEqualTo(new DateRange(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)));
        assertThat(plan.comparisonRange()).isEqualTo(new DateRange(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 28)));
    }
}
