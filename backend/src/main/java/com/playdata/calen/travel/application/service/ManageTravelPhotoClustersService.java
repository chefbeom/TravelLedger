package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.*;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import com.playdata.calen.travel.domain.policy.TravelClusterPagePolicy;
import java.util.List;

public final class ManageTravelPhotoClustersService implements ManageTravelPhotoClustersUseCase {
    private final TravelClusterDetailStore store;
    private final TravelClusterDetailSynchronizationPort synchronization;
    private final TravelSummaryInvalidationPort summaries;

    public ManageTravelPhotoClustersService(TravelClusterDetailStore store,
            TravelClusterDetailSynchronizationPort synchronization, TravelSummaryInvalidationPort summaries) {
        this.store = store;
        this.synchronization = synchronization;
        this.summaries = summaries;
    }
    @Override public Page page(Long owner, Long cluster, Integer page, Integer size, Long focus) {
        store.requireOwner(owner);
        int p = TravelClusterPagePolicy.page(page);
        return store.page(owner, cluster, p, TravelClusterPagePolicy.size(size), p == 0 ? focus : null);
    }
    @Override public Page changeRepresentative(Long owner, Long cluster, Long selected) {
        store.requireOwner(owner);
        synchronization.lockManualRefresh(owner);
        var current = store.detail(owner, cluster).or(() -> {
            synchronization.refreshMissing(owner);
            return store.detail(owner, cluster);
        }).orElseThrow(ManageTravelPhotoClustersService::missing);
        var photos = current.photos() == null ? List.<Media>of() : current.photos();
        if (photos.stream().noneMatch(photo -> photo.id() != null && photo.id().equals(selected))) {
            throw new BadRequestException("Selected photo is not part of this cluster.");
        }
        store.changeRepresentative(photos.stream().map(Media::id).toList(), selected);
        summaries.invalidateOwner(owner);
        synchronization.refreshNow(owner);
        synchronization.requestRefresh(owner);
        var refreshed = store.detail(owner, cluster)
                .or(() -> store.clusterForMedia(owner, selected).flatMap(id -> store.detail(owner, id)))
                .orElseThrow(ManageTravelPhotoClustersService::missing);
        var slice = TravelClusterPagePolicy.slice(refreshed.photos(), refreshed.representativeMediaId(),
                selected, 0, TravelClusterPagePolicy.DEFAULT_SIZE, Media::id, Media::expenseDate, Media::expenseTime, Media::uploadedAt);
        return new Page(refreshed, slice.photos(), slice.page(), slice.size(), slice.total(), slice.hasNext());
    }
    private static NotFoundException missing() { return new NotFoundException("Photo cluster not found."); }
}
