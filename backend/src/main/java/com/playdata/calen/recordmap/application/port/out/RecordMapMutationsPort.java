package com.playdata.calen.recordmap.application.port.out;

import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import java.time.LocalDateTime;
import java.util.List;

public interface RecordMapMutationsPort {
    Long savePlace(Long owner, Long id, PlaceDraft draft, List<Long> collections, LocalDateTime now);
    void deletePlace(Long id);
    CollectionResult saveCollection(Long owner, Long id, CollectionDraft draft);
    void revokeCollectionShares(Long owner, Long id);
    void detachCollection(Long id);
    void deleteCollection(Long id);
    Long saveVisit(Long owner, Long placeId, Long id, VisitDraft draft, LocalDateTime now);
    void deleteVisitPhotos(Long owner, Long id);
    void deleteVisit(Long id);
    Long createImportedPlace(Long owner, Long sourceId, PlaceDraft draft, List<Long> collections, LocalDateTime now);
    void addCollections(Long placeId, List<Long> collections, LocalDateTime now);
}
