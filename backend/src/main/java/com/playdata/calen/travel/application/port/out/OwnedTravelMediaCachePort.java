package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelMediaData.Download;

public interface OwnedTravelMediaCachePort {
    Download get(Long owner, Long media);
    void put(Long owner, Long media, Download download);
    void invalidate(Long owner, Long media);
}
