package com.playdata.calen.travel.adapter.out.cluster;

import com.playdata.calen.travel.application.port.out.TravelClusterRefreshPort;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelClusterRefreshAdapter implements TravelClusterRefreshPort {
    private final ObjectProvider<TravelClusterRefreshCoordinator> coordinators;
    private final RefreshTravelPhotoClustersUseCase projection;

    @Override public void refreshAfterChange(Long owner) {
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) TravelClusterRefreshRequests.enqueue(coordinator, owner);
        else projection.refreshSynchronously(owner, true);
    }
}
