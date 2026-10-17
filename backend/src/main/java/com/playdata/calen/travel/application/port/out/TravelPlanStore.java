package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelPlanData.*;

public interface TravelPlanStore {
    void requireActiveOwner(Long owner);
    void requireOwnedPlan(Long owner, Long planId);
    Long create(Long owner, Value value);
    void update(Long planId, Value value);
    Metrics metrics(Long owner, Long planId);
    void deletePlanAndRemainingRelations(Long planId);
}
