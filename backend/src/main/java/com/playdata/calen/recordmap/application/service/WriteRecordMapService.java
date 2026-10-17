package com.playdata.calen.recordmap.application.service;

import com.playdata.calen.recordmap.application.port.in.WriteRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.out.RecordMapCatalogPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapMutationsPort;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import com.playdata.calen.recordmap.domain.policy.RecordMapWritePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

public final class WriteRecordMapService implements WriteRecordMapUseCase {
    private final RecordMapCatalogPort catalog;
    private final RecordMapMutationsPort mutations;
    private final Clock clock;

    public WriteRecordMapService(RecordMapCatalogPort catalog, RecordMapMutationsPort mutations, Clock clock) {
        this.catalog = catalog;
        this.mutations = mutations;
        this.clock = clock;
    }

    @Override public Long savePlace(Long owner, Long id, PlaceDraft draft) {
        catalog.requireActiveOwner(owner);
        RecordMapWritePolicy.validateCoordinates(draft.latitude(), draft.longitude());
        if (id != null) catalog.requireOwnedPlace(owner, id);
        return mutations.savePlace(owner, id, draft, ownedCollections(owner, draft.collectionIds()), now());
    }

    @Override public void deletePlace(Long owner, Long id) {
        catalog.requireOwnedPlace(owner, id);
        RecordMapWritePolicy.requireNoVisits(catalog.visitCount(owner, id));
        mutations.deletePlace(id);
    }

    @Override public CollectionResult saveCollection(Long owner, Long id, CollectionDraft draft) {
        catalog.requireActiveOwner(owner);
        if (id != null) catalog.requireOwnedCollection(owner, id);
        return mutations.saveCollection(owner, id, draft);
    }

    @Override public void deleteCollection(Long owner, Long id) {
        catalog.requireOwnedCollection(owner, id);
        mutations.revokeCollectionShares(owner, id);
        mutations.detachCollection(id);
        mutations.deleteCollection(id);
    }

    @Override public Long saveVisit(Long owner, Long placeId, Long id, VisitDraft draft) {
        catalog.requireOwnedPlace(owner, placeId);
        if (id != null) RecordMapWritePolicy.requireSamePlace(catalog.ownedVisitPlace(owner, id, false), placeId);
        return mutations.saveVisit(owner, placeId, id, draft, now());
    }

    @Override public void deleteVisit(Long owner, Long id) {
        catalog.ownedVisitPlace(owner, id, true);
        mutations.deleteVisitPhotos(owner, id);
        mutations.deleteVisit(id);
    }

    @Override public Long importTravel(Long owner, TravelImport request) {
        // Lock the source before checking uniqueness or mutating collection membership.
        var source = catalog.lockTravelSource(owner, request.recordId());
        var draft = RecordMapWritePolicy.importedPlace(source);
        Long existing = catalog.importedPlaceId(owner, source.recordId());
        var collections = ownedCollections(owner, request.collectionIds());
        if (existing == null) return mutations.createImportedPlace(owner, source.recordId(), draft, collections, now());
        // Reimport augments memberships; it must not overwrite the user's edited place.
        mutations.addCollections(existing, collections, now());
        return existing;
    }

    private List<Long> ownedCollections(Long owner, List<Long> ids) {
        for (Long id : ids) catalog.requireOwnedCollection(owner, id);
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
