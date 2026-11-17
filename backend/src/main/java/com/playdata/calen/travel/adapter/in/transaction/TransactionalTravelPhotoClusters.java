package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.RefreshTravelPhotoClustersService;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.service.TravelClusterWithdrawalFence;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalTravelPhotoClusters implements RefreshTravelPhotoClustersUseCase {
    private final RefreshTravelPhotoClustersUseCase delegate;
    private final TravelClusterWithdrawalFence withdrawalFence;
    public TransactionalTravelPhotoClusters(TravelClusterProjectionStore projection, TravelPhotoClusterPolicy policy,
            TravelClusterSnapshotCachePort snapshots, TravelSummaryInvalidationPort summaries,
            TravelClusterWithdrawalFence withdrawalFence) {
        delegate = new RefreshTravelPhotoClustersService(projection, policy, snapshots, summaries);
        this.withdrawalFence = withdrawalFence;
    }
    // The DB adapter loads detached points in a short read transaction; CPU work holds no connection.
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PhotoCluster> compute(Long owner) { return delegate.compute(owner); }
    @Override @Transactional
    public void apply(Long owner, List<PhotoCluster> clusters) {
        withdrawalFence.requireAvailableOwner(owner);
        delegate.apply(owner, clusters);
    }
    @Override @Transactional
    public Snapshot refreshSynchronously(Long owner, boolean publishLegacySnapshot) {
        withdrawalFence.requireAvailableOwner(owner);
        return delegate.refreshSynchronously(owner, publishLegacySnapshot);
    }
    @Override @Transactional(readOnly = true)
    public Snapshot stored(Long owner) { return delegate.stored(owner); }
}
