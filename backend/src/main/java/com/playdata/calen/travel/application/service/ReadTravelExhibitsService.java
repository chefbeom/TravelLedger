package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.out.TravelExhibitReadStore;
import com.playdata.calen.travel.application.port.out.TravelPublicMediaTokenPort;
import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.policy.TravelExhibitPolicy;

public final class ReadTravelExhibitsService implements ReadTravelExhibitsUseCase {
    private final TravelExhibitReadStore store;
    private final TravelPublicMediaTokenPort tokens;
    public ReadTravelExhibitsService(TravelExhibitReadStore store, TravelPublicMediaTokenPort tokens) {
        this.store = store;
        this.tokens = tokens;
    }

    @Override
    public Page received(Long viewer, Integer page, Integer size) {
        store.requireActiveViewer(viewer);
        return store.completedShares(viewer, TravelExhibitPolicy.page(page), TravelExhibitPolicy.size(size));
    }

    @Override
    public Detail receivedDetail(Long viewer, Long share) {
        store.requireActiveViewer(viewer);
        var access = store.receivedShare(viewer, share);
        if (!TravelExhibitPolicy.completed(access.plan().status())) {
            throw new NotFoundException("Shared travel exhibit was not found.");
        }
        var detail = store.planDetail(access.plan().ownerId(), access.plan().id());
        return new Detail(access.id(), store.shareAttribution(access.id()), detail);
    }

    @Override
    public Download receivedMedia(Long viewer, Long share, Long mediaId) {
        store.requireActiveViewer(viewer);
        var access = store.receivedShare(viewer, share);
        if (!TravelExhibitPolicy.completed(access.plan().status())) {
            throw new NotFoundException("Shared exhibit media not found.");
        }
        var media = store.media(mediaId).orElseThrow(() -> new NotFoundException("Shared exhibit media not found."));
        if (!media.planId().equals(access.plan().id())) {
            throw new NotFoundException("Shared exhibit media not found.");
        }
        return media.download();
    }

    @Override
    public Download publicMedia(Long mediaId, String token) {
        if (!tokens.matches(mediaId, token)) {
            throw new NotFoundException("Shared media not found.");
        }
        var media = store.publicMedia(mediaId).orElseThrow(() -> new NotFoundException("Shared media not found."));
        if (!TravelExhibitPolicy.publicMedia(media)) {
            throw new NotFoundException("Shared media not found.");
        }
        return media.download();
    }

    @Override
    public Detail recordShareDetail(Long owner, Long plan, Long share) {
        store.ownedPlan(owner, plan);
        var detail = store.planDetail(owner, plan);
        return new Detail(share, store.ownerAttribution(plan), detail);
    }

    @Override
    public Download recordShareMedia(Long owner, Long plan, Long mediaId) {
        store.ownedPlan(owner, plan);
        var media = store.media(mediaId).orElseThrow(() -> new NotFoundException("Shared travel media not found."));
        if (!media.planId().equals(plan)) {
            throw new NotFoundException("Shared travel media not found.");
        }
        return media.download();
    }
}
