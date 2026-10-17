package com.playdata.calen.recordmap.service;

import com.playdata.calen.recordmap.adapter.support.RecordMapReadMapper;
import com.playdata.calen.recordmap.adapter.support.RecordMapWriteMapper;
import com.playdata.calen.recordmap.application.port.in.ReadRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.in.WriteRecordMapUseCase;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Filter;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility facade for existing internal callers; business decisions live in the use cases. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordMapService {
    private final ReadRecordMapUseCase reads;
    private final WriteRecordMapUseCase writes;

    public Overview overview(Long owner, Long collectionId, String state, LocalDate from, LocalDate to, int page) {
        return query(owner, collectionId, state, from, to, page, "", 100, false);
    }

    public Overview sharedOverview(String token, String state, LocalDate from, LocalDate to, int page) {
        return sharedQuery(token, state, from, to, page, "", 100, false);
    }

    public Overview query(Long owner, Long collectionId, String state, LocalDate from, LocalDate to, int page, String query, int size, boolean map) {
        return RecordMapReadMapper.response(reads.owned(owner, collectionId, new Filter(state, from, to, page, query, size, map)), null);
    }

    public Overview sharedQuery(String token, String state, LocalDate from, LocalDate to, int page, String query, int size, boolean map) {
        return RecordMapReadMapper.response(reads.shared(token, new Filter(state, from, to, page, query, size, map)), token);
    }

    public List<VisitView> placeVisits(Long owner, Long placeId, int page) {
        return RecordMapReadMapper.visits(reads.placeVisits(owner, placeId, page), null);
    }

    public Page<TravelCandidate> travelCandidates(Long owner, int page) {
        return RecordMapReadMapper.candidates(reads.travelCandidates(owner, page));
    }

    @Transactional
    public Long savePlace(Long owner, Long id, PlaceRequest request) {
        return writes.savePlace(owner, id, RecordMapWriteMapper.draft(request));
    }

    @Transactional
    public void deletePlace(Long owner, Long id) { writes.deletePlace(owner, id); }

    @Transactional
    public CollectionView saveCollection(Long owner, Long id, CollectionRequest request) {
        return RecordMapWriteMapper.response(writes.saveCollection(owner, id, RecordMapWriteMapper.draft(request)));
    }

    @Transactional
    public void deleteCollection(Long owner, Long id) { writes.deleteCollection(owner, id); }

    @Transactional
    public Long saveVisit(Long owner, Long placeId, Long id, VisitRequest request) {
        return writes.saveVisit(owner, placeId, id, RecordMapWriteMapper.draft(request));
    }

    @Transactional
    public void deleteVisit(Long owner, Long id) { writes.deleteVisit(owner, id); }

    @Transactional
    public Long importTravel(Long owner, TravelImportRequest request) {
        return writes.importTravel(owner, RecordMapWriteMapper.draft(request));
    }
}
