package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import java.util.List;

public interface ReadOwnedTravelMapUseCase {
    Overview overview(Long owner);
    Bundle markerBundle(Long owner, Long marker);
    List<Frame> frames(Long owner);
}
