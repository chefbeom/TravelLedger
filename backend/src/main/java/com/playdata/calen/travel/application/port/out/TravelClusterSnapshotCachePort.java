package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.Snapshot;

public interface TravelClusterSnapshotCachePort {
    void invalidateNow(Long owner);
    void invalidateAfterCommit(Long owner);
    void publishLegacy(Long owner, Snapshot snapshot);
}
