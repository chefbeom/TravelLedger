package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WriteTravelRecordServiceTest {
    private final TravelRecordStore records = mock(TravelRecordStore.class);
    private final TravelRatePort rates = mock(TravelRatePort.class);
    private final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    private final TravelClusterRefreshPort clusters = mock(TravelClusterRefreshPort.class);
    private final WriteTravelRecordService service = new WriteTravelRecordService(records, rates, summaries, clusters);
    private final Plan plan = new Plan(2L, "Trip", "#abcdef");
    private final LocalDate date = LocalDate.of(2026,10,15);

    @Test void expensePreservesRoundingMidnightAndDoesNotRefreshClusters() {
        when(records.ownedPlan(1L, 2L)).thenReturn(plan);
        when(records.create(eq(2L), any())).thenReturn(3L);
        var result = service.createExpense(1L, 2L, expense("USD", "3"));
        assertThat(result.id()).isEqualTo(3L);
        assertThat(result.plan().colorHex()).isEqualTo("#ABCDEF");
        var value = result.value();
        assertThat(value.amount()).isEqualTo(new BigDecimal("1.23"));
        assertThat(value.rate()).isEqualTo(new BigDecimal("3.000000"));
        assertThat(value.amountKrw()).isEqualTo(new BigDecimal("3.70"));
        assertThat(value.time()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(value.shared()).isFalse();
        assertThat(value.category()).isEqualTo("Food");
        assertThat(value.country()).isEqualTo("Japan");
        assertThat(value.region()).isNull();
        var order = inOrder(records, summaries);
        order.verify(records).ownedPlan(1L, 2L);
        order.verify(records).create(2L, value);
        order.verify(summaries).invalidateOwner(1L);
        verifyNoInteractions(rates, clusters);
    }

    @Test void memoryPreservesNullTimeAndUsesZeroMoneyAndExplicitSharing() {
        when(records.ownedPlan(1L, 2L)).thenReturn(plan);
        var result = service.createMemory(1L, 2L, memory(true));
        assertThat(result.value().time()).isNull();
        assertThat(result.value().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.value().amountKrw()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.value().rate()).isEqualTo(new BigDecimal("1.000000"));
        assertThat(result.value().currency()).isEqualTo("KRW");
        assertThat(result.value().shared()).isTrue();
        var order = inOrder(summaries, clusters);
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
        verifyNoInteractions(rates);
    }

    @Test void legacyNullKindMayBeUpdatedAsExpenseAndBecomesLedger() {
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, null)));
        var result = service.updateExpense(1L, 3L, expense("KRW", null));
        assertThat(result.value().type()).isEqualTo(TravelRecordType.LEDGER);
        verify(records).update(3L, result.value());
        verifyNoInteractions(clusters, rates);
    }

    @Test void mismatchedKindIsRejectedBeforeProviderOrMutation() {
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, TravelRecordType.MEMORY)));
        assertThatThrownBy(() -> service.updateExpense(1L, 3L, expense("USD", null))).isInstanceOf(NotFoundException.class).hasMessage("Travel ledger record not found.");
        assertThatThrownBy(() -> service.deleteExpense(1L, 3L)).isInstanceOf(NotFoundException.class);
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, null)));
        assertThatThrownBy(() -> service.updateMemory(1L, 3L, memory(false))).isInstanceOf(NotFoundException.class).hasMessage("Travel memory not found.");
        verify(records, never()).update(any(), any());
        verify(records, never()).removeMedia(any(), any());
        verifyNoInteractions(rates, summaries, clusters);
    }

    @Test void missingOrNonOwnedRecordHasTheSameTypeScopedNotFoundError() {
        assertThatThrownBy(() -> service.deleteMemory(1L, 3L)).isInstanceOf(NotFoundException.class).hasMessage("Travel memory not found.");
        assertThatThrownBy(() -> service.updateExpense(1L, 3L, expense("USD", null))).isInstanceOf(NotFoundException.class).hasMessage("Travel ledger record not found.");
        verifyNoInteractions(rates, summaries, clusters);
    }

    @Test void missingRateUsesThePortAndSixDecimalRounding() {
        when(records.ownedPlan(1L, 2L)).thenReturn(plan);
        when(rates.requiredRate("USD")).thenReturn(new BigDecimal("3.1234567"));
        var result = service.createExpense(1L, 2L, expense("USD", null));
        assertThat(result.value().rate()).isEqualTo(new BigDecimal("3.123457"));
    }

    @Test void memoryUpdateRechecksKindThenInvalidatesAndRefreshes() {
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, TravelRecordType.MEMORY)));
        var result = service.updateMemory(1L, 3L, memory(null));
        assertThat(result.value().shared()).isFalse();
        var order = inOrder(records, summaries, clusters);
        order.verify(records).ownedRecord(1L, 3L);
        order.verify(records).update(3L, result.value());
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
    }

    @Test void deletionRemovesOwnedAttachmentsBeforeRecordAndRefreshesOnlyMemory() {
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, TravelRecordType.MEMORY)));
        service.deleteMemory(1L, 3L);
        var order = inOrder(records, summaries, clusters);
        order.verify(records).ownedRecord(1L, 3L);
        order.verify(records).removeMedia(1L, 3L);
        order.verify(records).delete(3L);
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
        clearInvocations(summaries, clusters, records);
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, TravelRecordType.LEDGER)));
        service.deleteExpense(1L, 3L);
        verify(summaries).invalidateOwner(1L);
        verifyNoInteractions(clusters);
    }

    @Test void cleanupFailureCannotDeleteRecordOrPublishRefresh() {
        when(records.ownedRecord(1L, 3L)).thenReturn(Optional.of(new Access(3L, plan, TravelRecordType.MEMORY)));
        doThrow(new IllegalStateException("cleanup failed")).when(records).removeMedia(1L, 3L);
        assertThatThrownBy(() -> service.deleteMemory(1L, 3L)).hasMessage("cleanup failed");
        verify(records, never()).delete(any());
        verifyNoInteractions(summaries, clusters);
    }

    @Test void providerFailureDoesNotPersistOrInvalidate() {
        when(records.ownedPlan(1L, 2L)).thenReturn(plan);
        when(rates.requiredRate("USD")).thenThrow(new BadRequestException("rate unavailable"));
        assertThatThrownBy(() -> service.createExpense(1L, 2L, expense("USD", null))).hasMessage("rate unavailable");
        verify(records, never()).create(any(), any());
        verifyNoInteractions(summaries, clusters);
    }

    @Test void invalidCoordinatesDoNotPersistOrInvalidate() {
        when(records.ownedPlan(1L, 2L)).thenReturn(plan);
        var invalid = new MemoryDraft(date, null, "Spot", "Title", null, null, null, BigDecimal.ONE, null, false, null);
        assertThatThrownBy(() -> service.createMemory(1L, 2L, invalid)).isInstanceOf(BadRequestException.class).hasMessage("Latitude and longitude must be provided together.");
        verify(records, never()).create(any(), any());
        verifyNoInteractions(summaries, clusters, rates);
    }

    private ExpenseDraft expense(String currency, String rate) {
        return new ExpenseDraft(date, null, " Food ", " Lunch ", new BigDecimal("1.2345"), currency, rate == null ? null : new BigDecimal(rate),
                " Japan ", "  ", null, null, null, " note ");
    }
    private MemoryDraft memory(Boolean shared) { return new MemoryDraft(date, null, " Spot ", " Title ", null, null, null, null, null, shared, null); }
}
