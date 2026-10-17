package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelRouteData.*;

public interface TravelRouteStore {
    Plan ownedPlan(Long owner, Long plan);
    Access ownedRoute(Long owner, Long route);
    Long create(Long plan, Value value);
    void update(Long route, Value value);
    Result result(Long route);
    void removeFilesAndLinks(Long route);
    void replaceFiles(Long route,java.util.List<GpxFile> files);
    void delete(Long route);
}
