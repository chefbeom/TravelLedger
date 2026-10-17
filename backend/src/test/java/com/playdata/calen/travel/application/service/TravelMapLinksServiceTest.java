package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelMapLinksServiceTest {
    final TravelMapLinkStore store = mock(TravelMapLinkStore.class);
    final TravelMapLinkRuntimePort runtime = mock(TravelMapLinkRuntimePort.class);
    final TravelRoutePayloadPort payload = mock(TravelRoutePayloadPort.class);
    final TravelMapLinksService service = new TravelMapLinksService(store, runtime, payload, new TravelPhotoClusterPolicy());

    @Test void creationNormalizesScopeValidatesAllRowsBeforeGeneratingTokenAndKeepsRepositoryExclusionOrder() {
        var records = new LinkedHashSet<>(List.of(21L, 20L));
        when(store.ownedPlans(7L, List.of(11L, 10L))).thenReturn(Set.of(10L, 11L));
        when(store.ownedRecords(7L, Set.of(10L, 11L), new LinkedHashSet<>(List.of(20L, 21L)))).thenReturn(records);
        when(store.ownedMedia(7L, Set.of(10L, 11L), Set.of(30L))).thenReturn(Set.of(30L));
        when(store.ownedRoutes(7L, Set.of(10L, 11L), Set.of(40L))).thenReturn(Set.of(40L));
        var now = LocalDateTime.of(2026, 10, 16, 1, 2); when(runtime.now()).thenReturn(now); when(runtime.uniqueToken()).thenReturn("synthetic-token");
        service.create(7L, new Draft("  " + "x".repeat(170) + "  ", Arrays.asList(11L, 10L, 11L, null, 0L, -1L),
                List.of(20L, 21L, 20L), List.of(30L), List.of(40L)));
        var order = inOrder(store, runtime);
        order.verify(store).requireActiveOwner(7L); order.verify(store).ownedPlans(7L, List.of(11L, 10L));
        order.verify(store).ownedRecords(7L, Set.of(10L, 11L), Set.of(20L, 21L));
        order.verify(store).ownedMedia(7L, Set.of(10L, 11L), Set.of(30L)); order.verify(store).ownedRoutes(7L, Set.of(10L, 11L), Set.of(40L));
        order.verify(runtime).now(); order.verify(runtime).uniqueToken();
        order.verify(store).save(7L, "synthetic-token", "x".repeat(160), new Scope(List.of(11L, 10L), records, Set.of(30L), Set.of(40L)), now);
        verifyNoInteractions(payload);
    }

    @Test void emptyScopeAndForeignPlansCannotGenerateOrPersistTokens() {
        assertThatThrownBy(() -> service.create(7L, new Draft(null, Arrays.asList(null, 0L, -1L), null, null, null))).hasMessage("At least one travel plan is required.");
        assertThatThrownBy(() -> service.create(7L, new Draft(null, List.of(10L), null, null, null))).hasMessage("Only owned travel plans can be shared.");
        verifyNoInteractions(runtime); verify(store, never()).save(any(), any(), any(), any(), any());
    }

    @Test void eachExclusionCategoryIsCheckedBeforeLaterCategoriesOrAnyTokenGeneration() {
        when(store.ownedPlans(7L, List.of(10L))).thenReturn(Set.of(10L));
        assertThatThrownBy(() -> service.create(7L, new Draft(null, List.of(10L), List.of(20L), List.of(30L), List.of(40L))))
                .hasMessage("Excluded records must belong to selected travel plans.");
        verify(store, never()).ownedMedia(any(), any(), any()); verify(store, never()).ownedRoutes(any(), any(), any());
        assertThatThrownBy(() -> service.create(7L, new Draft(null, List.of(10L), null, List.of(30L), List.of(40L))))
                .hasMessage("Excluded media must belong to selected travel plans.");
        verify(store, never()).ownedRoutes(any(), any(), any());
        assertThatThrownBy(() -> service.create(7L, new Draft(null, List.of(10L), null, null, List.of(40L))))
                .hasMessage("Excluded routes must belong to selected travel plans.");
        verifyNoInteractions(runtime);
    }

    @Test void inactiveCreatorsFailBeforeReadingOrSavingScope() {
        doThrow(new NotFoundException("inactive")).when(store).requireActiveOwner(7L);
        assertThatThrownBy(() -> service.create(7L, new Draft(null, List.of(10L), null, null, null))).hasMessage("inactive");
        verify(store).requireActiveOwner(7L); verifyNoMoreInteractions(store); verifyNoInteractions(runtime, payload);
    }

    @Test void blankTokensAndInvalidPreviewNumbersFailBeforeAnyDatabaseRead() {
        assertThatThrownBy(() -> service.map("  ")).hasMessage("Shared travel map not found.");
        assertThatThrownBy(() -> service.cluster(null, 1L, 0, 12, null)).hasMessage("Shared travel map not found.");
        assertThatThrownBy(() -> service.media("", 1L)).hasMessage("Shared travel map not found.");
        assertThatThrownBy(() -> service.preview(null)).hasMessage("Shared travel map not found.");
        assertThatThrownBy(() -> service.previewMedia(null, 81)).hasMessage("Login map preview thumbnail not found.");
        verifyNoInteractions(store, runtime, payload);
    }

    @Test void loginPreviewCapsMarkersAndRoutesBeforeResolvingPathsAndRenumbersOnlyVisibleResults() {
        var scope = new Scope(List.of(10L), Set.of(), Set.of(), Set.of(1L));
        when(store.active("token")).thenReturn(new Link(7L, "owner", "Owner", "token", null, scope, null, null));
        when(store.ownedPlans(7L, List.of(10L))).thenReturn(Set.of(10L));
        when(store.markers(List.of(10L))).thenReturn(java.util.stream.LongStream.rangeClosed(1, 90).mapToObj(id ->
                new com.playdata.calen.travel.domain.model.TravelOwnedMapData.Marker(id, 10L, "Private", null, null, null, null, "Private title", null, null, null, java.math.BigDecimal.ONE, java.math.BigDecimal.TEN)).toList());
        var route = new com.playdata.calen.travel.domain.model.TravelRouteData.Value(null, "Private route", null, null, null, null, null, null, null, "opaque", null, null, null);
        when(store.routes(List.of(10L))).thenReturn(java.util.stream.LongStream.rangeClosed(1, 14).mapToObj(id ->
                new com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route(id, null, route, null)).toList());
        when(payload.resolve(null, null, "opaque")).thenReturn(List.of(
                new com.playdata.calen.travel.domain.model.TravelRouteData.Point(java.math.BigDecimal.ONE, java.math.BigDecimal.TEN),
                new com.playdata.calen.travel.domain.model.TravelRouteData.Point(java.math.BigDecimal.TEN, java.math.BigDecimal.ONE)));
        var preview = service.preview("token");
        assertThat(preview.markers()).hasSize(80); assertThat(preview.routes()).hasSize(12);
        assertThat(preview.markers().get(79).number()).isEqualTo(80); assertThat(preview.routes().get(11).number()).isEqualTo(12);
        verify(payload, times(12)).resolve(null, null, "opaque"); verify(store, never()).photos(any(), any());
    }

    @Test void deletedOrTransferredPlansGiveEmptyViewsWithoutReadingOwnerCachesOrRequiringAnActiveOwner() {
        var link = new Link(7L, "owner", "Owner", "stored-token", "Title", new Scope(List.of(10L), Set.of(), Set.of(), Set.of()), null, null);
        when(store.active("stored-token")).thenReturn(link);
        assertThat(service.map(" stored-token ").overview().plans()).isZero();
        assertThat(service.preview("stored-token").enabled()).isFalse();
        verify(store, never()).requireActiveOwner(any()); verify(store, never()).markers(any());
        verify(store, never()).photos(any(), any()); verifyNoInteractions(runtime, payload);
    }
}
