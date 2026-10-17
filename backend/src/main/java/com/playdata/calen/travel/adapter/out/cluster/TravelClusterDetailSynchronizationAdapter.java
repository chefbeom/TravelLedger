package com.playdata.calen.travel.adapter.out.cluster;

import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.TravelClusterDetailSynchronizationPort;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelClusterDetailSynchronizationAdapter implements TravelClusterDetailSynchronizationPort {
    private final ObjectProvider<TravelClusterRefreshCoordinator> coordinators;
    private final RefreshTravelPhotoClustersUseCase projection;

    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void awaitCurrent(Long owner) {
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) coordinator.ensureCurrent(owner);
    }
    @Override public void lockManualRefresh(Long owner) {
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) coordinator.lockManualRefresh(owner);
    }
    @Override public void refreshMissing(Long owner) {
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) TravelClusterRefreshRequests.enqueue(coordinator, owner);
        else projection.refreshSynchronously(owner, true);
    }
    @Override public void refreshNow(Long owner) {
        projection.refreshSynchronously(owner, coordinators.getIfAvailable() == null);
    }
    @Override public void requestRefresh(Long owner) {
        TravelClusterRefreshRequests.enqueue(coordinators.getIfAvailable(), owner);
    }
}
