package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import java.util.List;

public interface TravelOwnedMapReadStore {
    void requireActiveOwner(Long owner);
    int planCount(Long owner);
    List<MarkerSummary> markers(Long owner);
    List<Route> routes(Long owner);
    Location selectedMarker(Long owner, Long marker);
    List<Location> nearby(Long owner, Location selected, Bounds bounds, int limit);
    /** Inputs are server-resolved, owner-scoped markers, never browser-provided record scopes. */
    List<MarkerDetail> details(List<Location> markers);
    List<Frame> frames(Long owner);
}
