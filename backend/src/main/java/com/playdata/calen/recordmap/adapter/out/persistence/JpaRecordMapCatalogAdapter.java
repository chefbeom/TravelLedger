package com.playdata.calen.recordmap.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.recordmap.application.port.out.RecordMapCatalogPort;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.TravelSource;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaRecordMapCatalogAdapter implements RecordMapCatalogPort {
    private final AppUserService users;
    private final RecordPlaceRepository places;
    private final PlaceCollectionRepository collections;
    private final PlaceVisitRepository visits;
    private final TravelExpenseRecordRepository travelRecords;

    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }

    @Override public void requireOwnedPlace(Long owner, Long id) {
        places.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable);
    }

    @Override public void requireOwnedCollection(Long owner, Long id) {
        collections.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable);
    }

    @Override public Long ownedVisitPlace(Long owner, Long id, boolean lock) {
        var visit = (lock ? visits.lockOwned(id, owner) : visits.findByIdAndOwnerId(id, owner))
                .orElseThrow(RecordMapShareService::unavailable);
        return visit.getPlace().getId();
    }

    @Override public long visitCount(Long owner, Long placeId) { return visits.countByPlaceIdAndOwnerId(placeId, owner); }

    @Override public TravelSource lockTravelSource(Long owner, Long id) {
        var source = travelRecords.lockRecordMapSource(id, owner).orElseThrow(RecordMapShareService::unavailable);
        return new TravelSource(source.getId(), source.getTitle(), source.getPlaceName(), source.getCountry(), source.getRegion(),
                source.getLatitude() == null ? null : source.getLatitude().doubleValue(),
                source.getLongitude() == null ? null : source.getLongitude().doubleValue());
    }

    @Override public Long importedPlaceId(Long owner, Long sourceId) {
        return places.findByOwnerIdAndSourceTravelRecordId(owner, sourceId).map(p -> p.getId()).orElse(null);
    }
}
