package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ReadOwnedTravelPlansService;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.model.TravelPlanDetailData.Detail;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Transactional(readOnly = true)
public class TransactionalOwnedTravelPlans implements ReadOwnedTravelPlansUseCase {
    private final ReadOwnedTravelPlansService delegate;
    private final TravelSummaryCache summaries;
    private final TransactionTemplate reads;
    public TransactionalOwnedTravelPlans(TravelOwnedPlansStore store, TravelOwnedPlansCachePort cache, TravelRoutePayloadPort payload,
            TravelSummaryCache summaries, PlatformTransactionManager transactions) {
        delegate = new ReadOwnedTravelPlansService(store, cache, payload); this.summaries = summaries;
        reads = new TransactionTemplate(transactions); reads.setReadOnly(true);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Summary> plans(Long owner) { return summaries.execute(TravelSummaryCache.plansKey(owner), () -> reads.execute(tx -> delegate.plans(owner))); }
    @Override public Detail detail(Long owner, Long plan) { return delegate.detail(owner, plan); }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Portfolio portfolio(Long owner) { return summaries.execute(TravelSummaryCache.portfolioKey(owner), () -> reads.execute(tx -> delegate.portfolio(owner))); }
}
