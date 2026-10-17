package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefreshTravelPhotoClustersServiceTest {
    private final TravelClusterProjectionStore projection = mock(TravelClusterProjectionStore.class);
    private final TravelClusterSnapshotCachePort snapshots = mock(TravelClusterSnapshotCachePort.class);
    private final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    private final RefreshTravelPhotoClustersService service = new RefreshTravelPhotoClustersService(projection, new TravelPhotoClusterPolicy(), snapshots, summaries);

    @Test void computationUsesDetachedOwnerPointsAndHasNoWriteOrCacheSideEffects() {
        var point = point(); when(projection.loadPhotoPoints(7L)).thenReturn(List.of(point));
        var result = service.compute(7L);
        assertThat(result).hasSize(1); assertThat(result.get(0).representative()).isEqualTo(point);
        verify(projection).loadPhotoPoints(7L); verifyNoMoreInteractions(projection);
        verifyNoInteractions(snapshots, summaries);
    }
    @Test void emptyOwnerProducesNoClusters() {
        when(projection.loadPhotoPoints(7L)).thenReturn(List.of());
        assertThat(service.compute(7L)).isEmpty(); verifyNoInteractions(snapshots, summaries);
    }
    @Test void applyPreservesProjectionThenImmediateSnapshotThenSummaryThenCommitSnapshotOrder() {
        service.apply(7L, List.of());
        var order = inOrder(projection, snapshots, summaries);
        order.verify(projection).save(7L, List.of()); order.verify(snapshots).invalidateNow(7L);
        order.verify(summaries).invalidateOwner(7L); order.verify(snapshots).invalidateAfterCommit(7L);
        verify(projection, never()).loadSnapshot(any());
    }
    @Test void projectionFailureDoesNotPublishOrInvalidateCaches() {
        doThrow(new IllegalStateException("write failed")).when(projection).save(7L, List.of());
        assertThatThrownBy(() -> service.apply(7L, List.of())).hasMessage("write failed");
        verifyNoInteractions(snapshots, summaries);
    }
    @Test void synchronousLegacyFallbackPublishesTheStoredProjectionOnly() {
        Snapshot stored = new Snapshot(5, 2, List.of(), List.of());
        when(projection.loadPhotoPoints(7L)).thenReturn(List.of(point())); when(projection.loadSnapshot(7L)).thenReturn(stored);
        assertThat(service.refreshSynchronously(7L, true)).isSameAs(stored);
        var order = inOrder(projection, snapshots);
        order.verify(projection).loadPhotoPoints(7L); order.verify(projection).save(eq(7L), any());
        order.verify(projection).loadSnapshot(7L); order.verify(snapshots).publishLegacy(7L, stored);
        verifyNoMoreInteractions(snapshots); verifyNoInteractions(summaries);
    }
    @Test void synchronousCoordinatedRefreshEvictsWithoutPublishingAnUnversionedSnapshot() {
        when(projection.loadPhotoPoints(7L)).thenReturn(List.of());
        assertThat(service.refreshSynchronously(7L, false)).isEqualTo(Snapshot.empty());
        var order = inOrder(projection, snapshots);
        order.verify(projection).save(7L, List.of()); order.verify(projection).loadSnapshot(7L);
        order.verify(snapshots).invalidateNow(7L); order.verify(snapshots).invalidateAfterCommit(7L);
        verify(snapshots, never()).publishLegacy(any(), any()); verifyNoInteractions(summaries);
    }
    @Test void emptyLegacyProjectionStillPublishesTheCompatibleEmptySnapshot() {
        when(projection.loadPhotoPoints(7L)).thenReturn(List.of());
        assertThat(service.refreshSynchronously(7L, true)).isEqualTo(Snapshot.empty());
        verify(snapshots).publishLegacy(7L, Snapshot.empty());
    }
    @Test void storedReadDoesNotCalculateOrWrite() {
        Snapshot stored = Snapshot.empty(); when(projection.loadSnapshot(7L)).thenReturn(stored);
        assertThat(service.stored(7L)).isSameAs(stored);
        verify(projection).loadSnapshot(7L); verifyNoMoreInteractions(projection); verifyNoInteractions(snapshots, summaries);
    }
    private PhotoPoint point() {
        return new PhotoPoint(1L, 2L, 3L, "Trip", "#abcdef", LocalDate.of(2026,10,15), LocalTime.NOON, "Spot", "Place", "KR", "Seoul", "Cafe",
                new BigDecimal("37.5"), new BigDecimal("127"), LocalDateTime.of(2026,10,15,12,0), false);
    }
}
