package com.playdata.calen.recordmap.application.port.out;

import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, owner-scoped batch reads; no ORM objects or HTTP contracts leave this port. */
public interface RecordMapQueryPort {
    PlacePage places(Scope scope, Selection selection, boolean privateFields);
    Map<Long, Long> visitCounts(Long owner, List<Long> placeIds);
    List<VisitData> visits(Long owner, List<Long> placeIds, LocalDate from, LocalDate to, int limit);
    List<VisitData> placeVisits(Long owner, Long placeId, Window window);
    Map<Long, List<PhotoData>> visitPhotos(Long owner, List<Long> visitIds);
    Map<Long, TravelRecordData> travelRecords(Long owner, List<Long> recordIds);
    List<TravelPhoto> travelPhotos(Long owner, List<Long> recordIds, int limit);
    Map<Long, Long> collectionCounts(Long owner);
    Set<Long> sharedCollections(Long owner, LocalDateTime now);
    List<CollectionData> collections(Long owner);
    CollectionData collection(Long owner, Long id);
    CandidatePage travelCandidates(Long owner, Window window);
}
