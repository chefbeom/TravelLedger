package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.ledger.application.port.out.LedgerAnalysisDatasetStore;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrepareLedgerAnalysisDatasetServiceTest {
    final LedgerAnalysisDatasetStore store = mock(LedgerAnalysisDatasetStore.class);
    final PrepareLedgerAnalysisDatasetService service = new PrepareLedgerAnalysisDatasetService(store);
    final DateRange primary = new DateRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 11));
    final DateRange compare = new DateRange(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4));
    @Test void fullQueryOrderAndOwnerPropagationRemainUnchanged() {
        service.prepare(7L, new AnalysisPlan(LedgerAiAnalysisMode.COMPARISON, LedgerAiAnalysisPeriod.WEEK, null, primary, compare));
        var order = inOrder(store);
        order.verify(store).overview(7L, primary); order.verify(store).categories(7L, primary); order.verify(store).payments(7L, primary);
        order.verify(store).periods(7L, primary.to(), ComparisonUnit.WEEK, 8); order.verify(store).expenses(7L, primary); order.verify(store).topExpenses(7L, primary, 20);
        order.verify(store).overview(7L, compare); order.verify(store).categories(7L, compare); order.verify(store).payments(7L, compare); order.verify(store).expenses(7L, compare);
        order.verifyNoMoreInteractions();
    }
    @Test void weeklyDigestSkipsTopAndAbsentComparisonStaysNullPlusEmptyLists() {
        var result = service.prepare(7L, new AnalysisPlan(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.HALF_YEAR, LedgerAiComparisonPreset.WEEKLY_DIGEST, primary, null));
        verify(store, never()).topExpenses(any(), any(), anyInt()); verify(store).periods(7L, primary.to(), ComparisonUnit.MONTH, 6);
        assertThat(result.topExpenses()).isEmpty(); assertThat(result.comparisonOverview()).isNull();
        assertThat(result.comparisonCategoryBreakdown()).isEmpty(); assertThat(result.comparisonPaymentBreakdown()).isEmpty(); assertThat(result.comparisonExpenseEntries()).isEmpty();
    }
    @Test void failedPrimaryQueryNeverReadsTopOrComparisonAndNoNewFallbackIsInvented() {
        doThrow(new IllegalStateException("query failed")).when(store).expenses(7L, primary);
        assertThatThrownBy(() -> service.prepare(7L, new AnalysisPlan(LedgerAiAnalysisMode.COMPARISON, LedgerAiAnalysisPeriod.MONTH, null, primary, compare))).hasMessage("query failed");
        verify(store, never()).topExpenses(any(), any(), anyInt()); verify(store, never()).overview(7L, compare);
    }
}
