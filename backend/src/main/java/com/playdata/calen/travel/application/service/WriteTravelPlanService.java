package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;

public final class WriteTravelPlanService implements WriteTravelPlanUseCase {
    private final TravelPlanStore plans;
    private final TravelPlanAttachmentsPort attachments;
    private final TravelSummaryInvalidationPort summaries;
    private final TravelClusterRefreshPort clusters;

    public WriteTravelPlanService(TravelPlanStore plans, TravelPlanAttachmentsPort attachments,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        this.plans = plans;
        this.attachments = attachments;
        this.summaries = summaries;
        this.clusters = clusters;
    }

    @Override public Summary create(Long owner, Draft draft) {
        plans.requireActiveOwner(owner);
        Value value = TravelPlanPolicy.prepare(draft);
        var result = new Summary(plans.create(owner, value), value, Metrics.empty());
        changed(owner);
        return result;
    }

    @Override public Summary update(Long owner, Long planId, Draft draft) {
        plans.requireOwnedPlan(owner, planId);
        Value value = TravelPlanPolicy.prepare(draft);
        plans.update(planId, value);
        var result = new Summary(planId, value, plans.metrics(owner, planId));
        changed(owner);
        return result;
    }

    @Override public void delete(Long owner, Long planId) {
        plans.requireOwnedPlan(owner, planId);
        attachments.removePlanLinks(planId);
        attachments.removeMedia(owner, planId);
        attachments.removeRoutes(owner, planId);
        plans.deletePlanAndRemainingRelations(planId);
        changed(owner);
    }

    private void changed(Long owner) {
        summaries.invalidateOwner(owner);
        clusters.refreshAfterChange(owner);
    }
}
