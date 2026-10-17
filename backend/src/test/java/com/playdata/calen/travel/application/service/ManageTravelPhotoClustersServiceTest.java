package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManageTravelPhotoClustersServiceTest {
    final TravelClusterDetailStore store = mock(TravelClusterDetailStore.class);
    final TravelClusterDetailSynchronizationPort sync = mock(TravelClusterDetailSynchronizationPort.class);
    final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    final ManageTravelPhotoClustersService service = new ManageTravelPhotoClustersService(store, sync, summaries);

    @Test void pageChecksOwnerBeforeReadingAndNormalizesOnlyFirstPageFocus() {
        service.page(7L, 10L, -3, 100, 12L);
        var order = inOrder(store);
        order.verify(store).requireOwner(7L); order.verify(store).page(7L, 10L, 0, 36, 12L);
        service.page(7L, 10L, 2, 0, 12L);
        verify(store).page(7L, 10L, 2, 1, null);
        service.page(7L, 10L, null, null, null);
        verify(store).page(7L, 10L, 0, 12, null);
        verifyNoInteractions(sync, summaries);
    }
    @Test void invalidOwnerNeverReadsOrLocks() {
        doThrow(new NotFoundException("owner")).when(store).requireOwner(7L);
        assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, 12L)).hasMessage("owner");
        verify(store).requireOwner(7L); verifyNoMoreInteractions(store); verifyNoInteractions(sync, summaries);
    }
    @Test void changeKeepsLockMutationEvictionProjectionAndRequestOrder() {
        var before = detail(10L, 11L, List.of(photo(11L), photo(12L)));
        var after = detail(10L, 12L, before.photos());
        when(store.detail(7L, 10L)).thenReturn(Optional.of(before), Optional.of(after));
        var result = service.changeRepresentative(7L, 10L, 12L);
        assertThat(result.cluster()).isSameAs(after);
        assertThat(result.photos()).extracting(Media::id).containsExactly(12L, 11L);
        assertThat(result.page()).isZero(); assertThat(result.size()).isEqualTo(12); assertThat(result.totalPhotoCount()).isEqualTo(2);
        var order = inOrder(store, sync, summaries);
        order.verify(store).requireOwner(7L); order.verify(sync).lockManualRefresh(7L);
        order.verify(store).detail(7L, 10L); order.verify(store).changeRepresentative(List.of(11L, 12L), 12L);
        order.verify(summaries).invalidateOwner(7L); order.verify(sync).refreshNow(7L);
        order.verify(sync).requestRefresh(7L); order.verify(store).detail(7L, 10L);
        verifyNoMoreInteractions(store, sync, summaries);
    }
    @Test void missingProjectionGetsExactlyOneRefreshAttemptAfterLock() {
        when(store.detail(7L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, 12L)).isInstanceOf(NotFoundException.class).hasMessage("Photo cluster not found.");
        var order = inOrder(store, sync);
        order.verify(store).requireOwner(7L); order.verify(sync).lockManualRefresh(7L);
        order.verify(store).detail(7L, 10L); order.verify(sync).refreshMissing(7L); order.verify(store).detail(7L, 10L);
        verifyNoMoreInteractions(store, sync); verifyNoInteractions(summaries);
    }
    @Test void recoveredProjectionCanBeChanged() {
        var detail = detail(10L, 12L, List.of(photo(12L)));
        when(store.detail(7L, 10L)).thenReturn(Optional.empty(), Optional.of(detail), Optional.of(detail));
        assertThat(service.changeRepresentative(7L, 10L, 12L).photos()).hasSize(1);
        verify(sync).refreshMissing(7L); verify(store).changeRepresentative(List.of(12L), 12L);
    }
    @Test void selectedPhotoMustBeAMemberIncludingNullAndEmptyCases() {
        for (List<Media> photos : Arrays.asList(List.of(photo(11L)), Collections.<Media>emptyList(), null)) {
            for (Long selected : Arrays.asList(12L, null)) {
                reset(store, sync, summaries);
                when(store.detail(7L, 10L)).thenReturn(Optional.of(detail(10L, 11L, photos)));
                assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, selected))
                        .isInstanceOf(BadRequestException.class).hasMessage("Selected photo is not part of this cluster.");
                verify(store, never()).changeRepresentative(anyList(), any()); verifyNoInteractions(summaries);
                verify(sync).lockManualRefresh(7L); verifyNoMoreInteractions(sync);
            }
        }
    }
    @Test void movedClusterIsFoundThroughOwnersSelectedMediaAfterRefresh() {
        var before = detail(10L, 12L, List.of(photo(12L)));
        var moved = detail(20L, 12L, before.photos());
        when(store.detail(7L, 10L)).thenReturn(Optional.of(before), Optional.empty());
        when(store.clusterForMedia(7L, 12L)).thenReturn(Optional.of(20L));
        when(store.detail(7L, 20L)).thenReturn(Optional.of(moved));
        assertThat(service.changeRepresentative(7L, 10L, 12L).cluster()).isSameAs(moved);
    }
    @Test void absentRefreshedClusterStillFailsInsteadOfReturningStaleData() {
        when(store.detail(7L, 10L)).thenReturn(Optional.of(detail(10L, 12L, List.of(photo(12L)))), Optional.empty());
        when(store.clusterForMedia(7L, 12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, 12L)).isInstanceOf(NotFoundException.class);
    }
    @Test void failedMutationDoesNotEvictOrRefresh() {
        when(store.detail(7L, 10L)).thenReturn(Optional.of(detail(10L, 12L, List.of(photo(12L)))));
        doThrow(new IllegalStateException("SQL")).when(store).changeRepresentative(anyList(), any());
        assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, 12L)).hasMessage("SQL");
        verifyNoInteractions(summaries); verify(sync).lockManualRefresh(7L); verifyNoMoreInteractions(sync);
    }
    @Test void failedProjectionDoesNotDispatchOrReturnStaleResult() {
        when(store.detail(7L, 10L)).thenReturn(Optional.of(detail(10L, 12L, List.of(photo(12L)))));
        doThrow(new IllegalStateException("projection")).when(sync).refreshNow(7L);
        assertThatThrownBy(() -> service.changeRepresentative(7L, 10L, 12L)).hasMessage("projection");
        verify(summaries).invalidateOwner(7L); verify(sync, never()).requestRefresh(any());
        verify(store, times(1)).detail(7L, 10L);
    }
    static Detail detail(Long id, Long representative, List<Media> photos) {
        return new Detail(id, representative, 33L, null, null, 2, 2, null, false, photo(representative), photos);
    }
    static Media photo(Long id) {
        return new Media(id, 1L, "trip", "#ABCDEF", 33L, null, null, "photo.jpg", "image/jpeg", 1L, null,
                "owner", null, null, null, "place", null, null, null, null, null, null, null, null, null, null, false);
    }
}
