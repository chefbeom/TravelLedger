package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ReadOwnedTravelMapService;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Transactional(readOnly = true)
public class TransactionalOwnedTravelMap implements ReadOwnedTravelMapUseCase {
    private final ReadOwnedTravelMapService delegate;
    private final TravelOwnedMapClusterPort clusters;
    private final TravelSummaryCache summaries;
    private final TransactionTemplate overviewReads;
    public TransactionalOwnedTravelMap(TravelOwnedMapReadStore store, TravelOwnedMapCachePort cache, TravelOwnedMapClusterPort clusters,
            TravelRoutePayloadPort payload, TravelSummaryCache summaries, PlatformTransactionManager transactions) {
        this.delegate = new ReadOwnedTravelMapService(store, cache, clusters, payload); this.clusters = clusters; this.summaries = summaries;
        overviewReads = new TransactionTemplate(transactions);
        // The legacy overview may persist a missing cluster projection, unlike the other two reads.
        overviewReads.setReadOnly(false);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Overview overview(Long owner) {
        return summaries.execute(TravelSummaryCache.mapKey(owner), () -> {
            Long generation = clusters.awaitCurrent(owner);
            return overviewReads.execute(tx -> delegate.overview(owner, generation));
        });
    }
    @Override public Bundle markerBundle(Long owner, Long marker) { return delegate.markerBundle(owner, marker); }
    @Override public List<Frame> frames(Long owner) { return delegate.frames(owner); }
}
