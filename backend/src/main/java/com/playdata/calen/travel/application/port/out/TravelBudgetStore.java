package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelBudgetData.Value;

public interface TravelBudgetStore {
    void requireOwnedPlan(Long owner, Long planId);
    void requireOwnedBudget(Long owner, Long itemId);
    Long create(Long planId, Value value);
    void update(Long itemId, Value value);
    void delete(Long itemId);
}
