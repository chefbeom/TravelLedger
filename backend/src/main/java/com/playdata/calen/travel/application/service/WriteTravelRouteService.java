package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.domain.policy.TravelRoutePolicy;
import java.math.RoundingMode;

public final class WriteTravelRouteService implements WriteTravelRouteUseCase {
    private final TravelRouteStore routes;
    private final TravelRoutePayloadPort payload;
    private final TravelSummaryInvalidationPort summaries;
    public WriteTravelRouteService(TravelRouteStore routes, TravelRoutePayloadPort payload, TravelSummaryInvalidationPort summaries) {
        this.routes=routes;this.payload=payload;this.summaries=summaries;
    }
    @Override public Result create(Long owner, Long plan, Draft draft) {
        var access=routes.ownedPlan(owner,plan);
        Long id=routes.create(plan,prepare(draft,access.colorHex()));
        var response=routes.result(id);
        summaries.invalidateOwner(owner);
        return response;
    }
    @Override public Result update(Long owner, Long id, Draft draft) {
        var access=routes.ownedRoute(owner,id);
        routes.update(id,prepare(draft,access.plan().colorHex()));
        var response=routes.result(id);
        summaries.invalidateOwner(owner);
        return response;
    }
    @Override public void delete(Long owner, Long id) {
        routes.ownedRoute(owner,id);
        routes.removeFilesAndLinks(id);
        routes.delete(id);
        summaries.invalidateOwner(owner);
    }
    private Value prepare(Draft draft, String color) {
        var source=draft.sourceType()!=null?draft.sourceType():TravelRouteSourceType.MANUAL;
        var candidate=TravelRoutePolicy.normalizeRoutePointsForStorage(draft.points(),source);
        int budget=payload.characterBudget();
        String serialized=payload.encode(candidate,source);
        while(candidate.size()>2 && serialized.length()>budget) {
            int next=candidate.size()<=8?Math.max(2,candidate.size()-1):Math.max(2,(int)Math.ceil(candidate.size()*0.65));
            candidate=TravelRoutePolicy.thinRoutePoints(candidate,next);
            serialized=payload.encode(candidate,source);
        }
        return new Value(draft.routeDate(),draft.title().trim(),
                draft.transportMode()!=null?draft.transportMode():TravelRouteTransportMode.WALK,
                draft.distanceKm().setScale(3,RoundingMode.HALF_UP),draft.durationMinutes(),draft.stepCount(),source,
                TravelRoutePolicy.trimToNull(draft.startPlaceName()),TravelRoutePolicy.trimToNull(draft.endPlaceName()),
                payload.encode(candidate,source),TravelRoutePolicy.lineColor(draft.lineColorHex(),color),
                draft.lineStyle()!=null?draft.lineStyle():TravelRouteLineStyle.SOLID,TravelRoutePolicy.trimToNull(draft.memo()));
    }
}
