package com.playdata.calen.travel.adapter.out.cache;

import com.playdata.calen.travel.adapter.support.TravelClusterSnapshotMapper;
import com.playdata.calen.travel.application.port.out.TravelClusterSnapshotCachePort;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.Snapshot;
import com.playdata.calen.travel.service.TravelMyMapPhotoClusterSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelClusterSnapshotCacheAdapter implements TravelClusterSnapshotCachePort {
    private final TravelMyMapPhotoClusterSnapshotService cache;
    @Override public void invalidateNow(Long owner) { cache.delete(owner); }
    @Override public void invalidateAfterCommit(Long owner) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.delete(owner); }
        });
    }
    @Override public void publishLegacy(Long owner, Snapshot snapshot) { cache.save(owner, TravelClusterSnapshotMapper.response(snapshot)); }
}
