package com.playdata.calen.recordmap.adapter.support;

import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;

public final class RecordMapWriteMapper {
    private RecordMapWriteMapper() {}

    public static PlaceDraft draft(PlaceRequest request) {
        return new PlaceDraft(request.name(), request.memo(), request.country(), request.region(), request.latitude(),
                request.longitude(), request.visited(), request.collectionIds());
    }

    public static CollectionDraft draft(CollectionRequest request) { return new CollectionDraft(request.name(), request.memo(), request.colorHex()); }
    public static VisitDraft draft(VisitRequest request) { return new VisitDraft(request.title(), request.memo(), request.date(), request.time()); }
    public static TravelImport draft(TravelImportRequest request) { return new TravelImport(request.recordId(), request.collectionIds()); }
    public static CollectionView response(CollectionResult result) { return new CollectionView(result.id(), result.name(), result.memo(), result.colorHex(), 0, false); }
}
