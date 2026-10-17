package com.playdata.calen.recordmap.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.application.port.out.RecordMapCatalogPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapMutationsPort;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WriteRecordMapServiceTest {
    private final RecordMapCatalogPort catalog = mock(RecordMapCatalogPort.class);
    private final RecordMapMutationsPort mutations = mock(RecordMapMutationsPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private final WriteRecordMapService service = new WriteRecordMapService(catalog, mutations, clock);
    private final LocalDateTime now = LocalDateTime.now(clock);

    @Test void saveChecksOwnerAndAllCollectionsBeforeMutationAndDeduplicatesMemberships() {
        var ids = new ArrayList<>(List.of(3L, 4L, 3L));
        var draft = place(ids);
        ids.clear();
        when(mutations.savePlace(1L, 2L, draft, List.of(3L, 4L), now)).thenReturn(2L);
        assertThat(service.savePlace(1L, 2L, draft)).isEqualTo(2L);
        var order = inOrder(catalog, mutations);
        order.verify(catalog).requireActiveOwner(1L);
        order.verify(catalog).requireOwnedPlace(1L, 2L);
        order.verify(catalog).requireOwnedCollection(1L, 3L);
        order.verify(catalog).requireOwnedCollection(1L, 4L);
        order.verify(catalog).requireOwnedCollection(1L, 3L);
        order.verify(mutations).savePlace(1L, 2L, draft, List.of(3L, 4L), now);
    }

    @Test void forbiddenMembershipDoesNotReachPersistence() {
        doThrow(new NotFoundException("unavailable")).when(catalog).requireOwnedCollection(1L, 4L);
        assertThatThrownBy(() -> service.savePlace(1L, 2L, place(List.of(3L, 4L)))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(mutations);
    }

    @Test void nonFiniteCoordinatesAreRejectedBeforePlaceLookup() {
        var draft = new PlaceDraft("bad", null, null, null, Double.NaN, 127.0, false, null);
        assertThatThrownBy(() -> service.savePlace(1L, 2L, draft)).isInstanceOf(BadRequestException.class);
        verify(catalog, never()).requireOwnedPlace(any(), any());
        verifyNoInteractions(mutations);
    }

    @Test void placeWithVisitsCannotBeDeleted() {
        when(catalog.visitCount(1L, 2L)).thenReturn(1L);
        assertThatThrownBy(() -> service.deletePlace(1L, 2L)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mutations);
    }

    @Test void collectionDeletionRevokesSharesThenDetachesOnlyMemberships() {
        service.deleteCollection(1L, 3L);
        var order = inOrder(catalog, mutations);
        order.verify(catalog).requireOwnedCollection(1L, 3L);
        order.verify(mutations).revokeCollectionShares(1L, 3L);
        order.verify(mutations).detachCollection(3L);
        order.verify(mutations).deleteCollection(3L);
        verifyNoMoreInteractions(mutations);
    }

    @Test void editingVisitCannotMoveItToAnotherPlace() {
        when(catalog.ownedVisitPlace(1L, 9L, false)).thenReturn(88L);
        assertThatThrownBy(() -> service.saveVisit(1L, 2L, 9L, new VisitDraft("visit", null, LocalDate.now(clock), null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(mutations);
    }

    @Test void deletionLocksVisitBeforeRemovingPhotosAndRow() {
        service.deleteVisit(1L, 9L);
        var order = inOrder(catalog, mutations);
        order.verify(catalog).ownedVisitPlace(1L, 9L, true);
        order.verify(mutations).deleteVisitPhotos(1L, 9L);
        order.verify(mutations).deleteVisit(9L);
    }

    @Test void importLocksSourceBeforeUniquenessCheckAndValidatesCatalogBeforeSaving() {
        var source = new TravelSource(8L, "fallback", "place".repeat(40), "country".repeat(20), "region".repeat(30), 34.5, 135.5);
        when(catalog.lockTravelSource(1L, 8L)).thenReturn(source);
        when(catalog.importedPlaceId(1L, 8L)).thenReturn(null);
        when(mutations.createImportedPlace(eq(1L), eq(8L), any(), eq(List.of(3L)), eq(now))).thenReturn(2L);
        assertThat(service.importTravel(1L, new TravelImport(8L, List.of(3L)))).isEqualTo(2L);
        var order = inOrder(catalog, mutations);
        order.verify(catalog).lockTravelSource(1L, 8L);
        order.verify(catalog).importedPlaceId(1L, 8L);
        order.verify(catalog).requireOwnedCollection(1L, 3L);
        var draft = org.mockito.ArgumentCaptor.forClass(PlaceDraft.class);
        order.verify(mutations).createImportedPlace(eq(1L), eq(8L), draft.capture(), eq(List.of(3L)), eq(now));
        assertThat(draft.getValue().name()).hasSize(160);
        assertThat(draft.getValue().country()).hasSize(80);
        assertThat(draft.getValue().region()).hasSize(120);
        assertThat(draft.getValue().memo()).isNull();
    }

    @Test void reimportOnlyAddsMembershipsAndDoesNotOverwriteEditedPlace() {
        when(catalog.lockTravelSource(1L, 8L)).thenReturn(new TravelSource(8L, "original", null, null, null, 34.5, 135.5));
        when(catalog.importedPlaceId(1L, 8L)).thenReturn(2L);
        assertThat(service.importTravel(1L, new TravelImport(8L, List.of(3L)))).isEqualTo(2L);
        verify(mutations).addCollections(2L, List.of(3L), now);
        verifyNoMoreInteractions(mutations);
    }

    @Test void importRejectsMissingOrInvalidSourceCoordinatesWithoutWrites() {
        when(catalog.lockTravelSource(1L, 8L)).thenReturn(new TravelSource(8L, "original", null, null, null, null, 135.5));
        assertThatThrownBy(() -> service.importTravel(1L, new TravelImport(8L, null))).isInstanceOf(BadRequestException.class)
                .hasMessage("위치가 있는 여행 기록을 선택해 주세요.");
        when(catalog.lockTravelSource(1L, 8L)).thenReturn(new TravelSource(8L, "original", null, null, null, 91.0, 135.5));
        assertThatThrownBy(() -> service.importTravel(1L, new TravelImport(8L, null))).isInstanceOf(BadRequestException.class)
                .hasMessage("여행 원본의 위치 좌표를 먼저 확인해 주세요.");
        verify(catalog, never()).importedPlaceId(any(), any());
        verifyNoInteractions(mutations);
    }

    private PlaceDraft place(List<Long> collections) {
        return new PlaceDraft("cafe", "memo", "Korea", "Seoul", 37.55, 126.97, false, collections);
    }
}
