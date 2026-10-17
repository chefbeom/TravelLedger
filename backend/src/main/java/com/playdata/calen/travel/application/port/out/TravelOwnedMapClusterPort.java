package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Photos;

public interface TravelOwnedMapClusterPort {
    Long awaitCurrent(Long owner);
    Photos getOrBuild(Long owner, Long generation);
}
