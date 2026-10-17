package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import java.util.List;

public final class RefreshTravelPhotoClustersService implements RefreshTravelPhotoClustersUseCase {
    private final TravelClusterProjectionStore projection;
    private final TravelPhotoClusterPolicy policy;
    private final TravelClusterSnapshotCachePort snapshots;
    private final TravelSummaryInvalidationPort summaries;

    public RefreshTravelPhotoClustersService(TravelClusterProjectionStore projection, TravelPhotoClusterPolicy policy,
            TravelClusterSnapshotCachePort snapshots, TravelSummaryInvalidationPort summaries) {
        this.projection = projection;
        this.policy = policy;
        this.snapshots = snapshots;
        this.summaries = summaries;
    }

    @Override public List<PhotoCluster> compute(Long owner) { return policy.cluster(projection.loadPhotoPoints(owner)); }

    @Override public void apply(Long owner, List<PhotoCluster> clusters) {
        projection.save(owner, clusters);
        snapshots.invalidateNow(owner);
        summaries.invalidateOwner(owner);
        snapshots.invalidateAfterCommit(owner);
    }

    @Override public Snapshot refreshSynchronously(Long owner, boolean publishLegacySnapshot) {
        projection.save(owner, compute(owner));
        Snapshot snapshot = projection.loadSnapshot(owner);
        if (snapshot == null) snapshot = Snapshot.empty();
        if (publishLegacySnapshot) snapshots.publishLegacy(owner, snapshot);
        else {
            snapshots.invalidateNow(owner);
            snapshots.invalidateAfterCommit(owner);
        }
        return snapshot;
    }

    @Override public Snapshot stored(Long owner) { return projection.loadSnapshot(owner); }
}
