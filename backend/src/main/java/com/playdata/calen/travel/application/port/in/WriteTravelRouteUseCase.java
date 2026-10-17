package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelRouteData.*;

public interface WriteTravelRouteUseCase {
    Result create(Long owner, Long plan, Draft draft);
    Result update(Long owner, Long route, Draft draft);
    void delete(Long owner, Long route);
}
