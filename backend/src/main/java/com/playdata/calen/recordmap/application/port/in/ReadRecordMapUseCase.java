package com.playdata.calen.recordmap.application.port.in;

import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.util.List;

public interface ReadRecordMapUseCase {
    OverviewResult owned(Long owner, Long collectionId, Filter filter);
    OverviewResult shared(String token, Filter filter);
    List<VisitResult> placeVisits(Long owner, Long placeId, int page);
    CandidatePage travelCandidates(Long owner, int page);
}
