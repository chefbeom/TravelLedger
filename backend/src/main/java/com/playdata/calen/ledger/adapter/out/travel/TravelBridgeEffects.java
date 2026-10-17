package com.playdata.calen.ledger.adapter.out.travel;

import com.playdata.calen.ledger.application.port.out.TravelBridgeEffectsPort;
import com.playdata.calen.travel.adapter.out.cluster.TravelClusterRefreshRequests;
import com.playdata.calen.travel.adapter.support.TravelClusterSnapshotMapper;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelBridgeEffects implements TravelBridgeEffectsPort {
    private final TravelSummaryInvalidationPort summaries;
    private final ObjectProvider<TravelClusterRefreshCoordinator> coordinators;
    private final RefreshTravelPhotoClustersUseCase projection;
    @Override public void travelChanged(Long owner) {
        summaries.invalidateOwner(owner);
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) TravelClusterRefreshRequests.enqueue(coordinator, owner);
        else TravelClusterSnapshotMapper.response(projection.refreshSynchronously(owner, true));
    }
}
