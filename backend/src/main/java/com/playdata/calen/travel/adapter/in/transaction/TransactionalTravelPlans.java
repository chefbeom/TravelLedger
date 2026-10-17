package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.WriteTravelPlanService;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalTravelPlans implements WriteTravelPlanUseCase {
    private final WriteTravelPlanUseCase delegate;

    public TransactionalTravelPlans(TravelPlanStore plans, TravelPlanAttachmentsPort attachments,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        delegate = new WriteTravelPlanService(plans, attachments, summaries, clusters);
    }

    @Override public Summary create(Long owner, Draft draft) { return delegate.create(owner, draft); }
    @Override public Summary update(Long owner, Long id, Draft draft) { return delegate.update(owner, id, draft); }
    @Override public void delete(Long owner, Long id) { delegate.delete(owner, id); }
}
