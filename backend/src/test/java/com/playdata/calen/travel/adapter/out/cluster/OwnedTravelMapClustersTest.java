package com.playdata.calen.travel.adapter.out.cluster;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.Snapshot;
import com.playdata.calen.travel.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;

class OwnedTravelMapClustersTest {
    @SuppressWarnings("unchecked")
    final ObjectProvider<TravelClusterRefreshCoordinator> providers = mock(ObjectProvider.class);
    final TravelClusterRefreshCoordinator coordinator = mock(TravelClusterRefreshCoordinator.class);
    final RefreshTravelPhotoClustersUseCase projection = mock(RefreshTravelPhotoClustersUseCase.class);
    final TravelMyMapPhotoClusterSnapshotService snapshots = mock(TravelMyMapPhotoClusterSnapshotService.class);
    final OwnedTravelMapClusters clusters = new OwnedTravelMapClusters(providers, projection, snapshots);
    final TravelMyMapPhotoClusterSnapshot empty = new TravelMyMapPhotoClusterSnapshot(0, 0, List.of(), List.of(), List.of());

    @Test void absentCoordinatorHasNoGenerationWhilePresentCoordinatorWaitsForCurrentGeneration() {
        assertThat(clusters.awaitCurrent(7L)).isNull();
        when(providers.getIfAvailable()).thenReturn(coordinator); when(coordinator.ensureCurrent(7L)).thenReturn(19L);
        assertThat(clusters.awaitCurrent(7L)).isEqualTo(19L);
        verify(coordinator).ensureCurrent(7L); verifyNoInteractions(projection, snapshots);
    }

    @Test void cachedPlainAndVersionedSnapshotsSkipProjectionAndNeverRewriteTheCache() {
        when(snapshots.get(7L)).thenReturn(empty); when(snapshots.get(7L, 19L)).thenReturn(empty);
        assertThat(clusters.getOrBuild(7L, null).clusterCount()).isZero();
        assertThat(clusters.getOrBuild(7L, 19L).markerCount()).isZero();
        verify(snapshots).get(7L); verify(snapshots).get(7L, 19L); verifyNoMoreInteractions(snapshots);
        verifyNoInteractions(projection, providers);
    }

    @Test void storedProjectionIsCachedUnderTheRequestedGenerationWithoutRefreshing() {
        when(projection.stored(7L)).thenReturn(new Snapshot(4, 2, List.of(), List.of()));
        var result = clusters.getOrBuild(7L, 19L);
        assertThat(result.markerCount()).isEqualTo(4); assertThat(result.clusterCount()).isEqualTo(2);
        var order = inOrder(snapshots, projection);
        order.verify(snapshots).get(7L, 19L); order.verify(projection).stored(7L);
        order.verify(snapshots).save(7L, 19L, new TravelMyMapPhotoClusterSnapshot(4, 2, List.of(), List.of(), List.of()));
        verifyNoMoreInteractions(projection); verifyNoInteractions(providers);
    }

    @Test void storedProjectionWithoutGenerationKeepsThePlainSaveContract() {
        when(projection.stored(7L)).thenReturn(Snapshot.empty());
        clusters.getOrBuild(7L, null);
        verify(snapshots).save(7L, empty); verifyNoInteractions(providers);
    }

    @Test void currentGenerationWithNoRowsCachesEmptyAndDoesNotTriggerAnotherRefresh() {
        assertThat(clusters.getOrBuild(7L, 19L).pins()).isEmpty();
        verify(snapshots).save(7L, 19L, empty); verify(projection).stored(7L);
        verifyNoMoreInteractions(projection); verifyNoInteractions(providers);
    }

    @Test void missingPlainSnapshotSchedulesAfterCommitInsteadOfRunningSynchronousProjection() {
        when(providers.getIfAvailable()).thenReturn(coordinator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(clusters.getOrBuild(7L, null).clusters()).isEmpty();
            verify(coordinator).markDirtyInCurrentTransaction(7L); verify(coordinator, never()).dispatch(any());
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(coordinator).dispatch(7L); verify(snapshots, never()).save(any(), any());
            verify(projection, never()).refreshSynchronously(any(), anyBoolean());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void synchronousFallbackIsOnlyUsedWithoutCoordinatorAndKeepsLegacyCacheOwnership() {
        when(projection.refreshSynchronously(7L, true)).thenReturn(new Snapshot(2, 1, List.of(), List.of()));
        assertThat(clusters.getOrBuild(7L, null).markerCount()).isEqualTo(2);
        var order = inOrder(snapshots, projection, providers);
        order.verify(snapshots).get(7L); order.verify(projection).stored(7L); order.verify(providers).getIfAvailable();
        order.verify(projection).refreshSynchronously(7L, true);
        verify(snapshots, never()).save(any(), any());
    }
}
