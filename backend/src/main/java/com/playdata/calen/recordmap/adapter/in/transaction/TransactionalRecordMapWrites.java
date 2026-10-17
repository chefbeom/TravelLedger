package com.playdata.calen.recordmap.adapter.in.transaction;

import com.playdata.calen.recordmap.application.port.in.WriteRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.out.RecordMapCatalogPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapMutationsPort;
import com.playdata.calen.recordmap.application.service.WriteRecordMapService;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalRecordMapWrites implements WriteRecordMapUseCase {
    private final WriteRecordMapUseCase delegate;

    public TransactionalRecordMapWrites(RecordMapCatalogPort catalog, RecordMapMutationsPort mutations) {
        delegate = new WriteRecordMapService(catalog, mutations, Clock.systemDefaultZone());
    }

    @Override public Long savePlace(Long owner, Long id, PlaceDraft draft) { return delegate.savePlace(owner, id, draft); }
    @Override public void deletePlace(Long owner, Long id) { delegate.deletePlace(owner, id); }
    @Override public CollectionResult saveCollection(Long owner, Long id, CollectionDraft draft) { return delegate.saveCollection(owner, id, draft); }
    @Override public void deleteCollection(Long owner, Long id) { delegate.deleteCollection(owner, id); }
    @Override public Long saveVisit(Long owner, Long placeId, Long id, VisitDraft draft) { return delegate.saveVisit(owner, placeId, id, draft); }
    @Override public void deleteVisit(Long owner, Long id) { delegate.deleteVisit(owner, id); }
    @Override public Long importTravel(Long owner, TravelImport request) { return delegate.importTravel(owner, request); }
}
