package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.TravelMapLinksService;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalTravelMapLinks implements TravelMapLinksUseCase {
    private final TravelMapLinksService delegate;
    public TransactionalTravelMapLinks(TravelMapLinkStore store, TravelMapLinkRuntimePort runtime, TravelRoutePayloadPort payload, TravelPhotoClusterPolicy clustering) {
        delegate = new TravelMapLinksService(store, runtime, payload, clustering);
    }
    @Override @Transactional public Link create(Long owner, Draft draft) { return delegate.create(owner, draft); }
    @Override @Transactional public PublicMap map(String token) { return delegate.map(token); }
    @Override @Transactional public Cluster cluster(String token, Long cluster, Integer page, Integer size, Long focus) { return delegate.cluster(token, cluster, page, size, focus); }
    @Override @Transactional public Download media(String token, Long media) { return delegate.media(token, media); }
    @Override @Transactional public Preview preview(String token) { return delegate.preview(token); }
    @Override @Transactional public Download previewMedia(String token, int number) { return delegate.previewMedia(token, number); }
}
