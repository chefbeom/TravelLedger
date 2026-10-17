package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Overview;

public interface TravelOwnedMapCachePort {
    Overview get(Long owner, Long generation);
    void publish(Long owner, Long generation, Overview overview);
}
