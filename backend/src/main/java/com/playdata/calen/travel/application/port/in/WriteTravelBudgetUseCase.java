package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelBudgetData.*;

public interface WriteTravelBudgetUseCase {
    Result create(Long owner, Long planId, Draft draft);
    Result update(Long owner, Long itemId, Draft draft);
    void delete(Long owner, Long itemId);
}
