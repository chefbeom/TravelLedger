package com.playdata.calen.recordmap.application.port.out;

import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.TravelSource;

public interface RecordMapCatalogPort {
    void requireActiveOwner(Long owner);
    void requireOwnedPlace(Long owner, Long id);
    void requireOwnedCollection(Long owner, Long id);
    Long ownedVisitPlace(Long owner, Long id, boolean lock);
    long visitCount(Long owner, Long placeId);
    TravelSource lockTravelSource(Long owner, Long id);
    Long importedPlaceId(Long owner, Long sourceId);
}
