package com.playdata.calen.travel.adapter.out.cluster;

import static org.mockito.Mockito.*;

import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class TravelClusterRefreshAdapterTest {
    @SuppressWarnings("unchecked")
    @Test void requestsFromNewAndLegacyPathsMergeAndDispatchAfterCommit() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class);
        var providers = mock(ObjectProvider.class);
        var projection = mock(RefreshTravelPhotoClustersUseCase.class);
        when(providers.getIfAvailable()).thenReturn(coordinator);
        var adapter = new TravelClusterRefreshAdapter(providers, projection);
        TransactionSynchronizationManager.initSynchronization();
        try {
            TravelClusterRefreshRequests.enqueue(coordinator, 7L);
            adapter.refreshAfterChange(7L);
            adapter.refreshAfterChange(8L);
            verify(coordinator).markDirtyInCurrentTransaction(7L);
            verify(coordinator).markDirtyInCurrentTransaction(8L);
            verify(coordinator, never()).dispatch(any());
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(coordinator).dispatch(7L);
            verify(coordinator).dispatch(8L);
            verifyNoInteractions(projection);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void rollbackDoesNotDispatchDirtyRequests() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            TravelClusterRefreshRequests.enqueue(coordinator, 7L);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(coordinator).markDirtyInCurrentTransaction(7L);
            verify(coordinator, never()).dispatch(any());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void requestOutsideTransactionUsesExistingCoordinatorTransactionPath() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class);
        TravelClusterRefreshRequests.enqueue(coordinator, 7L);
        verify(coordinator).markDirty(7L);
        verifyNoMoreInteractions(coordinator);
    }

    @SuppressWarnings("unchecked")
    @Test void missingCoordinatorUsesIndependentSynchronousProjection() {
        ObjectProvider<TravelClusterRefreshCoordinator> providers = mock(ObjectProvider.class);
        var projection = mock(RefreshTravelPhotoClustersUseCase.class);
        new TravelClusterRefreshAdapter(providers, projection).refreshAfterChange(7L);
        verify(projection).refreshSynchronously(7L, true);
    }
}
