package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManageLedgerAnalysisHistoryServiceTest {
    final LedgerAnalysisHistoryStore store = mock(LedgerAnalysisHistoryStore.class);
    final LedgerAnalysisHistoryCodec codec = mock(LedgerAnalysisHistoryCodec.class);
    final ManageLedgerAnalysisHistoryService service = new ManageLedgerAnalysisHistoryService(store, codec,
            () -> Instant.parse("2026-01-01T00:00:00Z"), () -> ZoneId.of("Asia/Seoul"));

    @Test void searchChecksOwnerBeforeDateValidationAndDoesNotQueryOnInvalidRange() {
        var from = LocalDate.of(2026, 1, 2);
        assertThatThrownBy(() -> service.search(7L, new Filter(null, null, from, from.minusDays(1), null, -1, 100)))
                .hasMessage("조회 시작일은 종료일보다 이후일 수 없습니다.");
        verify(store).requireActiveOwner(7L); verify(store, never()).search(any(), any());
    }
    @Test void searchUsesInclusiveCalendarDatesAndClampsOnlyTheRequestPagination() {
        var from = LocalDate.of(2026, 1, 1); var to = from.plusDays(3);
        var page = new HistoryPage(List.of(), 9, 3, 29, 10);
        var filter = new Search(LedgerAiAnalysisMode.COMPARISON, LedgerAiAnalysisPeriod.WEEK, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), false, 0, 50);
        when(store.search(7L, filter)).thenReturn(page);
        assertThat(service.search(7L, new Filter(filter.mode(), filter.periodType(), from, to, false, -5, 999))).isSameAs(page);
        var order = inOrder(store); order.verify(store).requireActiveOwner(7L); order.verify(store).search(7L, filter);
    }
    @Test void missingDetailsAndSingleDeletesKeepTheirDifferentNotFoundMessages() {
        when(store.find(7L, 9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(7L, 9L)).hasMessage("AI 분석 이력을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.delete(7L, 9L)).hasMessage("AI analysis history was not found.");
        verify(store, times(2)).requireActiveOwner(7L);
    }
    @Test void deleteAllKeepsOwnerScopeAndTheOriginalCountWithoutInventingAProcessingGuard() {
        when(store.deleteAll(7L)).thenReturn(3); assertThat(service.deleteAll(7L)).isEqualTo(3);
        var order = inOrder(store); order.verify(store).requireActiveOwner(7L); order.verify(store).deleteAll(7L);
        verifyNoMoreInteractions(store); verifyNoInteractions(codec);
    }
    @Test void internalMatchingDoesNotRevalidateTheCallerOrParseResults() {
        var key = new MatchKey(7L, "p", "m", null, null, null, null, null, null, null);
        when(store.completed(key)).thenReturn(Optional.empty()); when(store.processing(key)).thenReturn(Optional.empty());
        assertThat(service.completed(key)).isEmpty(); assertThat(service.processing(key)).isEmpty();
        verify(store).completed(key); verify(store).processing(key); verifyNoMoreInteractions(store); verifyNoInteractions(codec);
    }
    @Test void processingNeverParsesOrDisplaysEvenAPresentResult() {
        var history = row(LedgerAiAnalysisStatus.PROCESSING, "{}", null);
        assertThat(service.readStored(history)).isNull(); assertThat(service.readReusable(history)).isNull(); verifyNoInteractions(codec);
    }
    @Test void corruptOrEmptyCompletedResultsHaveVisibleFallbackButAreNeverReusable() {
        var history = row(LedgerAiAnalysisStatus.COMPLETED, "broken", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(codec.read("broken")).thenThrow(new IllegalStateException("private parser detail"));
        assertThat(service.readStored(history).warnings()).containsExactly("저장된 AI 분석 결과를 읽지 못했습니다. 재분석을 실행해 주세요.");
        assertThat(service.readReusable(history)).isNull();
        when(codec.read("null")).thenReturn(null);
        assertThat(service.readStored(row(LedgerAiAnalysisStatus.COMPLETED, "null", null)).warnings()).containsExactly("저장된 AI 분석 결과가 비어 있습니다. 재분석을 실행해 주세요.");
    }
    @Test void fallbackPreservesSummaryWhitespaceAndUsesLazyClockOrLocalZoneButNeverBoth() {
        var history = row(LedgerAiAnalysisStatus.FAILED, "valid-but-unused", null);
        var now = Instant.parse("2026-02-01T00:00:00Z");
        var withoutDate = new ManageLedgerAnalysisHistoryService(store, codec, () -> now, () -> { throw new AssertionError("zone must be lazy"); });
        assertThat(withoutDate.readStored(history).generatedAt()).isEqualTo(now);
        var withDate = new ManageLedgerAnalysisHistoryService(store, codec, () -> { throw new AssertionError("clock must be lazy"); }, () -> ZoneId.of("Asia/Seoul"));
        var result = withDate.readStored(row(LedgerAiAnalysisStatus.FAILED, "unused", LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertThat(result.generatedAt()).isEqualTo(Instant.parse("2025-12-31T15:00:00Z")); assertThat(result.summary()).isEqualTo(" raw summary ");
        assertThat(result.totalExpense()).isZero(); verifyNoInteractions(codec);
    }
    private StoredHistory row(LedgerAiAnalysisStatus status, String json, LocalDateTime created) {
        return new StoredHistory(new Summary(9L, LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH, null, status,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, "raw-model", "raw-title", " raw summary ", null, created), json);
    }
}
