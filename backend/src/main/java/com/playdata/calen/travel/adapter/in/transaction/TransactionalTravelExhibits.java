package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ReadTravelExhibitsService;
import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalTravelExhibits implements ReadTravelExhibitsUseCase {
    private final ReadTravelExhibitsUseCase delegate;
    public TransactionalTravelExhibits(TravelExhibitReadStore store, TravelPublicMediaTokenPort tokens) {
        delegate = new ReadTravelExhibitsService(store, tokens);
    }
    @Override public Page received(Long viewer, Integer page, Integer size) { return delegate.received(viewer, page, size); }
    @Override public Detail receivedDetail(Long viewer, Long share) { return delegate.receivedDetail(viewer, share); }
    @Override public Download receivedMedia(Long viewer, Long share, Long media) { return delegate.receivedMedia(viewer, share, media); }
    @Override public Download publicMedia(Long media, String token) { return delegate.publicMedia(media, token); }
    @Override public Detail recordShareDetail(Long owner, Long plan, Long share) { return delegate.recordShareDetail(owner, plan, share); }
    @Override public Download recordShareMedia(Long owner, Long plan, Long media) { return delegate.recordShareMedia(owner, plan, media); }
}
