package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReflectTravelLedgerServiceTest {
    final LedgerTravelStore store = mock(LedgerTravelStore.class);
    final LinkedLedgerWritePort writer = mock(LinkedLedgerWritePort.class);
    final TravelBridgeEffectsPort effects = mock(TravelBridgeEffectsPort.class);
    final ReflectTravelLedgerService service = new ReflectTravelLedgerService(store, writer, effects);
    final Plan plan = new Plan(21L, 7L, "Trip", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    final Entry entry = new Entry(31L, 7L, EntryType.EXPENSE, LocalDate.of(2026, 10, 16), null, "Title", null,
            new BigDecimal("12000"), null, null, null, new Named(11L, "Food"), new Named(12L, "Lunch"), new Ref(13L), null, null);

    @Test void forwardAllocatesOrReactivatesReferencesThenBuildsRequestAndOnlyThenSelectsCreateOrUpdate() {
        when(store.ownedTravel(7L, 41L)).thenReturn(Optional.of(travel("LEDGER")));
        when(store.group(7L, "여행")).thenReturn(11L); when(store.detail(11L, "Food", "Food")).thenReturn(12L); when(store.payment(7L, "여행 미지정")).thenReturn(13L);
        var result = mock(LedgerResult.class); when(writer.create(eq(7L), any())).thenReturn(result);
        assertThat(service.toLedger(7L, 41L)).isSameAs(result);
        var order = inOrder(store, writer);
        order.verify(store).ownedTravel(7L, 41L); order.verify(store).group(7L, "여행"); order.verify(store).detail(11L, "Food", "Food");
        order.verify(store).payment(7L, "여행 미지정"); order.verify(store).linkedLedger(7L, 41L); order.verify(writer).create(eq(7L), any());
        when(store.linkedLedger(7L, 41L)).thenReturn(Optional.of(51L)); when(writer.update(eq(7L), eq(51L), any())).thenReturn(result);
        assertThat(service.toLedger(7L, 41L)).isSameAs(result); verify(writer).update(eq(7L), eq(51L), any()); verifyNoInteractions(effects);
    }

    @Test void forwardMissingMemoryAndLegacyNullKindsKeepTheirDifferentErrorsWithoutCreatingReferences() {
        assertThatThrownBy(() -> service.toLedger(7L, 41L)).hasMessage("Travel ledger record not found.");
        when(store.ownedTravel(7L, 41L)).thenReturn(Optional.of(travel("MEMORY")));
        assertThatThrownBy(() -> service.toLedger(7L, 41L)).hasMessage("Travel ledger record not found.");
        when(store.ownedTravel(7L, 41L)).thenReturn(Optional.of(travel(null)));
        assertThatThrownBy(() -> service.toLedger(7L, 41L)).hasMessage("Only travel ledger records can be reflected into the household ledger.");
        verify(store, never()).group(any(), any()); verifyNoInteractions(writer, effects);
    }

    @Test void reverseResolvesPlanBeforeLedgerAndRejectsAnExistingRecordFromAnotherPlanBeforeSaving() {
        assertThatThrownBy(() -> service.toTravel(7L, 21L, 31L)).hasMessage("Travel plan not found.");
        verify(store, never()).ownedEntry(any(), any());
        when(store.ownedPlan(7L, 21L)).thenReturn(Optional.of(plan));
        assertThatThrownBy(() -> service.toTravel(7L, 21L, 31L)).hasMessage("Ledger entry not found.");
        var linked = linkedEntry(41L); when(store.ownedEntry(7L, 31L)).thenReturn(Optional.of(linked));
        when(store.linkedTravel(7L, 41L)).thenReturn(Optional.of(new LinkedTravel(41L, 99L, true)));
        assertThatThrownBy(() -> service.toTravel(7L, 21L, 31L)).hasMessage("Ledger entry is already linked to another travel record.");
        verify(store, never()).saveTravel(any(), any(), any()); verifyNoInteractions(writer, effects);
    }

    @Test void reverseSavesTravelBeforeUpdatingLedgerAndProjectsResponseBeforeAnyCacheOrClusterEffect() {
        when(store.ownedPlan(7L, 21L)).thenReturn(Optional.of(plan)); when(store.ownedEntry(7L, 31L)).thenReturn(Optional.of(entry));
        when(store.saveTravel(eq(21L), isNull(), any())).thenReturn(41L);
        var result = mock(TravelResult.class); when(store.travelResult(41L)).thenReturn(result);
        assertThat(service.toTravel(7L, 21L, 31L)).isSameAs(result);
        var value = ArgumentCaptor.forClass(TravelValue.class); var request = ArgumentCaptor.forClass(Write.class);
        var order = inOrder(store, writer, effects);
        order.verify(store).ownedPlan(7L, 21L); order.verify(store).ownedEntry(7L, 31L); order.verify(store).saveTravel(eq(21L), isNull(), value.capture());
        order.verify(writer).update(eq(7L), eq(31L), request.capture()); order.verify(store).travelResult(41L); order.verify(effects).travelChanged(7L);
        assertThat(value.getValue().time()).isEqualTo(LocalTime.MIDNIGHT); assertThat(value.getValue().shared()).isFalse();
        assertThat(request.getValue().time()).isNull(); assertThat(request.getValue().plan()).isEqualTo(21L); assertThat(request.getValue().record()).isEqualTo(41L);
    }

    @Test void missingLinkedRecordCreatesANewOneButAnExistingSharedFlagIsPreserved() {
        when(store.ownedPlan(7L, 21L)).thenReturn(Optional.of(plan)); when(store.ownedEntry(7L, 31L)).thenReturn(Optional.of(linkedEntry(999L)));
        when(store.saveTravel(eq(21L), isNull(), any())).thenReturn(41L);
        service.toTravel(7L, 21L, 31L); verify(store).saveTravel(eq(21L), isNull(), any());
        when(store.ownedEntry(7L, 31L)).thenReturn(Optional.of(linkedEntry(41L)));
        when(store.linkedTravel(7L, 41L)).thenReturn(Optional.of(new LinkedTravel(41L, 21L, true)));
        when(store.saveTravel(eq(21L), eq(41L), any())).thenReturn(41L);
        service.toTravel(7L, 21L, 31L);
        verify(store).saveTravel(eq(21L), eq(41L), argThat(value -> Boolean.TRUE.equals(value.shared())));
        when(store.linkedTravel(7L, 41L)).thenReturn(Optional.of(new LinkedTravel(41L, 21L, null)));
        service.toTravel(7L, 21L, 31L);
        verify(store).saveTravel(eq(21L), eq(41L), argThat(value -> Boolean.FALSE.equals(value.shared())));
    }

    @Test void writerAndProjectionFailuresDoNotRunTravelEffects() {
        when(store.ownedPlan(7L, 21L)).thenReturn(Optional.of(plan)); when(store.ownedEntry(7L, 31L)).thenReturn(Optional.of(entry));
        when(store.saveTravel(eq(21L), isNull(), any())).thenReturn(41L);
        doThrow(new IllegalStateException("write failed")).when(writer).update(eq(7L), eq(31L), any());
        assertThatThrownBy(() -> service.toTravel(7L, 21L, 31L)).hasMessage("write failed");
        verify(store, never()).travelResult(any()); verifyNoInteractions(effects);
        reset(writer); when(store.travelResult(41L)).thenThrow(new IllegalStateException("projection failed"));
        assertThatThrownBy(() -> service.toTravel(7L, 21L, 31L)).hasMessage("projection failed"); verifyNoInteractions(effects);
    }

    private Travel travel(String kind) { return new Travel(41L, plan, kind, entry.date(), entry.time(), "Food", "Title", entry.amount(), "KRW", BigDecimal.ONE, entry.amount(), null, null, null, null); }
    private Entry linkedEntry(Long id) {
        return new Entry(entry.id(), entry.owner(), entry.type(), entry.date(), entry.time(), entry.title(), entry.memo(), entry.amount(), entry.currency(), entry.foreignAmount(),
                entry.rate(), entry.group(), entry.detail(), entry.payment(), 21L, id);
    }
}
