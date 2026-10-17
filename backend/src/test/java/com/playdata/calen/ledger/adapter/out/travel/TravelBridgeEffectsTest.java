package com.playdata.calen.ledger.adapter.out.travel;

import static org.mockito.Mockito.*;

import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;

class TravelBridgeEffectsTest {
    final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    @SuppressWarnings("unchecked") final ObjectProvider<TravelClusterRefreshCoordinator> providers = mock(ObjectProvider.class);
    final RefreshTravelPhotoClustersUseCase projection = mock(RefreshTravelPhotoClustersUseCase.class);
    final TravelBridgeEffects effects = new TravelBridgeEffects(summaries, providers, projection);

    @Test void coordinatorAbsenceKeepsSynchronousProjectionAfterCacheInvalidation() {
        when(projection.refreshSynchronously(7L, true)).thenReturn(TravelPhotoClusterData.Snapshot.empty());
        effects.travelChanged(7L);
        var order = inOrder(summaries, providers, projection); order.verify(summaries).invalidateOwner(7L); order.verify(providers).getIfAvailable(); order.verify(projection).refreshSynchronously(7L, true);
    }
    @Test void coordinatorPathMarksWithinTransactionAndDispatchesOnlyAfterCommit() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class); when(providers.getIfAvailable()).thenReturn(coordinator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            effects.travelChanged(7L); effects.travelChanged(7L);
            verify(summaries, times(2)).invalidateOwner(7L); verify(coordinator).markDirtyInCurrentTransaction(7L); verify(coordinator, never()).dispatch(any());
            TransactionSynchronizationUtils.triggerAfterCommit(); verify(coordinator).dispatch(7L); verifyNoInteractions(projection);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void rollbackDoesNotDispatchAndInvalidationFailureDoesNotTouchProjectionOrCoordinator() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class); when(providers.getIfAvailable()).thenReturn(coordinator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            effects.travelChanged(7L); TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(coordinator, never()).dispatch(any());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        reset(providers, summaries); doThrow(new IllegalStateException("cache failed")).when(summaries).invalidateOwner(7L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> effects.travelChanged(7L)).hasMessage("cache failed");
        verifyNoInteractions(providers, projection);
    }
}
