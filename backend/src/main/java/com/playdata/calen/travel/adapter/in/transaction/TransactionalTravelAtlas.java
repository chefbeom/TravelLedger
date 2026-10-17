package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ReadTravelAtlasService;
import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalTravelAtlas implements ReadTravelAtlasUseCase {
    private final ReadTravelAtlasService delegate;
    public TransactionalTravelAtlas(TravelAtlasStore store, TravelAtlasMediaTokenPort tokens, TravelRoutePayloadPort payload, TravelPhotoClusterPolicy clustering) {
        delegate = new ReadTravelAtlasService(store, tokens, payload, clustering);
    }
    @Override public Overview overview(Long viewer) { return delegate.overview(viewer); }
    @Override public Cluster cluster(Long viewer, Long cluster, Integer page, Integer size, Long focus) { return delegate.cluster(viewer, cluster, page, size, focus); }
    @Override public Feed community(Long viewer, Integer page, Integer size) { return delegate.community(viewer, page, size); }
}
