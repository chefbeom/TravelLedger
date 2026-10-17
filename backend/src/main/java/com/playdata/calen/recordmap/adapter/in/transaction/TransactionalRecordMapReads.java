package com.playdata.calen.recordmap.adapter.in.transaction;

import com.playdata.calen.recordmap.application.port.in.ReadRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.out.RecordMapQueryPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapReadAccessPort;
import com.playdata.calen.recordmap.application.service.ReadRecordMapService;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalRecordMapReads implements ReadRecordMapUseCase {
    private final ReadRecordMapUseCase delegate;

    public TransactionalRecordMapReads(RecordMapReadAccessPort access, RecordMapQueryPort query) {
        delegate = new ReadRecordMapService(access, query, Clock.systemDefaultZone());
    }

    @Override public OverviewResult owned(Long owner, Long collectionId, Filter filter) { return delegate.owned(owner, collectionId, filter); }
    @Override public OverviewResult shared(String token, Filter filter) { return delegate.shared(token, filter); }
    @Override public List<VisitResult> placeVisits(Long owner, Long placeId, int page) { return delegate.placeVisits(owner, placeId, page); }
    @Override public CandidatePage travelCandidates(Long owner, int page) { return delegate.travelCandidates(owner, page); }
}
