package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import java.util.List;

/** The generation/lease coordinator owns whether a detached result is still safe to apply. */
public interface RefreshTravelPhotoClustersUseCase {
    List<PhotoCluster> compute(Long owner);
    void apply(Long owner, List<PhotoCluster> clusters);
    Snapshot refreshSynchronously(Long owner, boolean publishLegacySnapshot);
    Snapshot stored(Long owner);
}
