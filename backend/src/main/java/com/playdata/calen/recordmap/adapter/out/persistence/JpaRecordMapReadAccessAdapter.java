package com.playdata.calen.recordmap.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.recordmap.application.port.out.RecordMapReadAccessPort;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Scope;
import com.playdata.calen.recordmap.repository.PlaceCollectionRepository;
import com.playdata.calen.recordmap.repository.RecordPlaceRepository;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class JpaRecordMapReadAccessAdapter implements RecordMapReadAccessPort {
    private final AppUserService users;
    private final PlaceCollectionRepository collections;
    private final RecordPlaceRepository places;
    private final RecordMapShareService sharing;

    @Override public Scope owned(Long owner, Long collectionId) {
        users.getRequiredUser(owner);
        if (collectionId != null) collections.findByIdAndOwnerId(collectionId, owner).orElseThrow(RecordMapShareService::unavailable);
        return new Scope(owner, collectionId, false, true);
    }

    @Override public Scope shared(String token) {
        var share = sharing.resolve(token);
        return new Scope(share.getOwnerId(), share.getCollection().getId(), true, share.isIncludeVisits());
    }

    @Override public void requireOwnedPlace(Long owner, Long placeId) {
        places.findByIdAndOwnerId(placeId, owner).orElseThrow(RecordMapShareService::unavailable);
    }
}
