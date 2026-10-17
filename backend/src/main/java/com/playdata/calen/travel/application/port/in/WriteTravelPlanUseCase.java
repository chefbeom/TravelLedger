package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelPlanData.*;

public interface WriteTravelPlanUseCase {
    Summary create(Long owner, Draft draft);
    Summary update(Long owner, Long planId, Draft draft);
    void delete(Long owner, Long planId);
}
