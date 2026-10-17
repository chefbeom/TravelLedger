package com.playdata.calen.recordmap.application.port.in;

import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;

public interface WriteRecordMapUseCase {
    Long savePlace(Long owner, Long id, PlaceDraft draft);
    void deletePlace(Long owner, Long id);
    CollectionResult saveCollection(Long owner, Long id, CollectionDraft draft);
    void deleteCollection(Long owner, Long id);
    Long saveVisit(Long owner, Long placeId, Long id, VisitDraft draft);
    void deleteVisit(Long owner, Long id);
    Long importTravel(Long owner, TravelImport request);
}
