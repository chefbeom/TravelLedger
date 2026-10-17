package com.playdata.calen.travel.adapter.out.cluster;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;

class TravelClusterDetailSynchronizationAdapterTest {
    @SuppressWarnings("unchecked") final ObjectProvider<TravelClusterRefreshCoordinator> providers = mock(ObjectProvider.class);
    final RefreshTravelPhotoClustersUseCase projection = mock(RefreshTravelPhotoClustersUseCase.class);
    final TravelClusterRefreshCoordinator coordinator = mock(TravelClusterRefreshCoordinator.class);
    final TravelClusterDetailSynchronizationAdapter adapter = new TravelClusterDetailSynchronizationAdapter(providers, projection);

    @Test void absentCoordinatorKeepsLegacySynchronousRefreshAndNoDispatch() {
        adapter.awaitCurrent(7L); adapter.lockManualRefresh(7L); adapter.refreshMissing(7L); adapter.refreshNow(7L); adapter.requestRefresh(7L);
        verify(projection,times(2)).refreshSynchronously(7L,true); verifyNoMoreInteractions(projection); verifyNoInteractions(coordinator);
    }
    @Test void presentCoordinatorKeepsReadinessLockAndNonLegacySnapshotMode() {
        when(providers.getIfAvailable()).thenReturn(coordinator);
        adapter.awaitCurrent(7L); adapter.lockManualRefresh(7L); adapter.refreshNow(7L);
        var order=inOrder(coordinator,projection);
        order.verify(coordinator).ensureCurrent(7L); order.verify(coordinator).lockManualRefresh(7L);
        order.verify(projection).refreshSynchronously(7L,false);
    }
    @Test void missingAndFinalRefreshShareTheExistingDirtyBatchAndOnlyDispatchOnCommit() {
        when(providers.getIfAvailable()).thenReturn(coordinator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.refreshMissing(7L); adapter.requestRefresh(7L); adapter.requestRefresh(8L);
            verify(coordinator).markDirtyInCurrentTransaction(7L); verify(coordinator).markDirtyInCurrentTransaction(8L);
            verify(coordinator,never()).dispatch(any());
            var callbacks=TransactionSynchronizationManager.getSynchronizations(); assertThat(callbacks).hasSize(1);
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(coordinator).dispatch(7L); verify(coordinator).dispatch(8L);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void rollbackCompletionDoesNotDispatch() {
        when(providers.getIfAvailable()).thenReturn(coordinator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.requestRefresh(7L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(coordinator).markDirtyInCurrentTransaction(7L); verify(coordinator,never()).dispatch(any());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
