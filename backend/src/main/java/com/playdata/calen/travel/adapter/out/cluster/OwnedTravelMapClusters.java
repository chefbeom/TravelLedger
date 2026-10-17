package com.playdata.calen.travel.adapter.out.cluster;

import com.playdata.calen.travel.adapter.support.TravelClusterSnapshotMapper;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.TravelOwnedMapClusterPort;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Photos;
import com.playdata.calen.travel.service.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OwnedTravelMapClusters implements TravelOwnedMapClusterPort {
    private final ObjectProvider<TravelClusterRefreshCoordinator> coordinators;
    private final RefreshTravelPhotoClustersUseCase projection;
    private final TravelMyMapPhotoClusterSnapshotService snapshots;
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long awaitCurrent(Long owner) {
        var coordinator = coordinators.getIfAvailable();
        return coordinator == null ? null : coordinator.ensureCurrent(owner);
    }
    @Override public Photos getOrBuild(Long owner, Long generation) {
        var snapshot = generation == null ? snapshots.get(owner) : snapshots.get(owner, generation);
        if (snapshot != null) return TravelOwnedMapMapper.photos(snapshot);
        snapshot = TravelClusterSnapshotMapper.response(projection.stored(owner));
        if (snapshot == null && generation != null) snapshot = empty();
        if (snapshot != null) {
            if (generation == null) snapshots.save(owner, snapshot); else snapshots.save(owner, generation, snapshot);
            return TravelOwnedMapMapper.photos(snapshot);
        }
        var coordinator = coordinators.getIfAvailable();
        if (coordinator != null) {
            TravelClusterRefreshRequests.enqueue(coordinator, owner);
            return TravelOwnedMapMapper.photos(empty());
        }
        return TravelOwnedMapMapper.photos(TravelClusterSnapshotMapper.response(projection.refreshSynchronously(owner, true)));
    }
    private TravelMyMapPhotoClusterSnapshot empty() { return new TravelMyMapPhotoClusterSnapshot(0, 0, List.of(), List.of(), List.of()); }
}
