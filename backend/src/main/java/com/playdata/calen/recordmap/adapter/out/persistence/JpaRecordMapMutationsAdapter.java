package com.playdata.calen.recordmap.adapter.out.persistence;

import com.playdata.calen.recordmap.application.port.out.RecordMapMutationsPort;
import com.playdata.calen.recordmap.domain.*;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.recordmap.service.RecordMapMediaService;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Joins the use-case transaction so validated managed entities retain their @Version and locks. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaRecordMapMutationsAdapter implements RecordMapMutationsPort {
    private final RecordPlaceRepository places;
    private final PlaceCollectionRepository collections;
    private final PlaceVisitRepository visits;
    private final PlacePhotoRepository photos;
    private final RecordMapShareRepository shares;
    private final RecordMapMediaService media;

    @Override public Long savePlace(Long owner, Long id, PlaceDraft draft, List<Long> collectionIds, LocalDateTime now) {
        RecordPlace place = id == null ? new RecordPlace() : places.getReferenceById(id);
        place.setOwnerId(owner);
        place.setName(draft.name().trim());
        place.setMemo(draft.memo());
        place.setCountry(draft.country());
        place.setRegion(draft.region());
        place.setLatitude(draft.latitude());
        place.setLongitude(draft.longitude());
        place.setManuallyVisited(draft.visited());
        place.setCollections(collectionReferences(collectionIds));
        place.setUpdatedAt(now);
        return places.saveAndFlush(place).getId();
    }

    @Override public void deletePlace(Long id) { places.delete(places.getReferenceById(id)); }

    @Override public CollectionResult saveCollection(Long owner, Long id, CollectionDraft draft) {
        PlaceCollection collection = id == null ? new PlaceCollection() : collections.getReferenceById(id);
        collection.setOwnerId(owner);
        collection.setName(draft.name().trim());
        collection.setMemo(draft.memo());
        collection.setColorHex(draft.colorHex());
        var saved = collections.save(collection);
        return new CollectionResult(saved.getId(), saved.getName(), saved.getMemo(), saved.getColorHex());
    }

    @Override public void revokeCollectionShares(Long owner, Long id) {
        shares.deleteAll(shares.findAllByOwnerIdAndCollectionIdOrderByCreatedAtDesc(owner, id));
        shares.flush();
    }

    @Override public void detachCollection(Long id) { places.detachCollection(id); }

    @Override public void deleteCollection(Long id) { collections.delete(collections.getReferenceById(id)); }

    @Override public Long saveVisit(Long owner, Long placeId, Long id, VisitDraft draft, LocalDateTime now) {
        var place = places.getReferenceById(placeId);
        PlaceVisit visit = id == null ? new PlaceVisit() : visits.getReferenceById(id);
        visit.setOwnerId(owner);
        visit.setPlace(place);
        visit.setTitle(draft.title().trim());
        visit.setMemo(draft.memo());
        visit.setVisitDate(draft.date());
        visit.setVisitTime(draft.time());
        visits.saveAndFlush(visit);
        place.setUpdatedAt(now);
        return visit.getId();
    }

    @Override public void deleteVisitPhotos(Long owner, Long id) {
        // Storage deletion remains an after-commit callback; rollback retains both rows and files.
        media.deleteRows(photos.findAllByOwnerIdAndVisitIdInOrderByIdAsc(owner, List.of(id)));
    }

    @Override public void deleteVisit(Long id) { visits.delete(visits.getReferenceById(id)); }

    @Override public Long createImportedPlace(Long owner, Long sourceId, PlaceDraft draft, List<Long> collectionIds, LocalDateTime now) {
        var place = new RecordPlace();
        place.setOwnerId(owner);
        place.setSourceTravelRecordId(sourceId);
        place.setName(draft.name());
        place.setCountry(draft.country());
        place.setRegion(draft.region());
        place.setLatitude(draft.latitude());
        place.setLongitude(draft.longitude());
        place.getCollections().addAll(collectionReferences(collectionIds));
        place.setUpdatedAt(now);
        return places.saveAndFlush(place).getId();
    }

    @Override public void addCollections(Long placeId, List<Long> collectionIds, LocalDateTime now) {
        var place = places.getReferenceById(placeId);
        place.getCollections().addAll(collectionReferences(collectionIds));
        place.setUpdatedAt(now);
        places.saveAndFlush(place);
    }

    private Set<PlaceCollection> collectionReferences(List<Long> ids) {
        Set<PlaceCollection> result = new LinkedHashSet<>();
        for (Long id : ids) result.add(collections.getReferenceById(id));
        return result;
    }
}
