package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import java.util.List;

public interface TravelClusterProjectionStore {
    List<PhotoPoint> loadPhotoPoints(Long owner);
    void save(Long owner, List<PhotoCluster> clusters);
    Snapshot loadSnapshot(Long owner);
}
