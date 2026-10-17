package com.playdata.calen.recordmap.adapter.out.persistence;

import com.playdata.calen.recordmap.application.port.out.RecordMapQueryPort;
import com.playdata.calen.recordmap.domain.PlaceCollection;
import com.playdata.calen.recordmap.domain.PlaceVisit;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class JpaRecordMapQueryAdapter implements RecordMapQueryPort {
    private final RecordPlaceRepository places;
    private final PlaceVisitRepository visits;
    private final PlacePhotoRepository photos;
    private final PlaceCollectionRepository collections;
    private final RecordMapShareRepository shares;
    private final TravelExpenseRecordRepository travelRecords;
    private final TravelMediaAssetRepository travelPhotos;

    @Override public PlacePage places(Scope scope, Selection selection, boolean privateFields) {
        var window = selection.window();
        var result = places.findVisible(scope.ownerId(), scope.collectionId(), selection.state(), selection.from(), selection.to(),
                searchPattern(selection.keyword()), privateFields,
                PageRequest.of(window.page(), window.size(), Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        var content = result.getContent().stream().map(p -> new PlaceData(p.getId(), p.getName(), p.getMemo(), p.getCountry(),
                p.getRegion(), p.getLatitude(), p.getLongitude(), p.isManuallyVisited(), p.getSourceTravelRecordId(),
                p.getCollections().stream().map(PlaceCollection::getId).toList())).toList();
        return new PlacePage(content, result.getTotalElements(), result.getTotalPages());
    }

    @Override public Map<Long, Long> visitCounts(Long owner, List<Long> placeIds) {
        return visits.countVisits(owner, placeIds).stream()
                .collect(Collectors.toMap(PlaceVisitRepository.VisitCount::getPlaceId, PlaceVisitRepository.VisitCount::getTotal));
    }

    @Override public List<VisitData> visits(Long owner, List<Long> placeIds, LocalDate from, LocalDate to, int limit) {
        return visits.findVisibleVisits(owner, placeIds, from, to, PageRequest.of(0, limit)).stream().map(this::visit).toList();
    }

    @Override public List<VisitData> placeVisits(Long owner, Long placeId, Window window) {
        return visits.findAllByOwnerIdAndPlaceIdInOrderByVisitDateDescVisitTimeDescIdDesc(owner, List.of(placeId),
                PageRequest.of(window.page(), window.size())).stream().map(this::visit).toList();
    }

    @Override public Map<Long, List<PhotoData>> visitPhotos(Long owner, List<Long> visitIds) {
        return photos.findAllByOwnerIdAndVisitIdInOrderByIdAsc(owner, visitIds).stream().collect(Collectors.groupingBy(
                p -> p.getVisit().getId(), Collectors.mapping(p -> new PhotoData(p.getId(), p.getOriginalName(), Origin.RECORD_MAP), Collectors.toList())));
    }

    @Override public Map<Long, TravelRecordData> travelRecords(Long owner, List<Long> recordIds) {
        return travelRecords.findAllByIdInAndPlanOwnerId(recordIds, owner).stream().collect(Collectors.toMap(r -> r.getId(),
                r -> new TravelRecordData(r.getId(), r.getTitle(), r.getMemo(), r.getExpenseDate(), r.getExpenseTime(), r.getPlan().getId(), r.getPlan().getName())));
    }

    @Override public List<TravelPhoto> travelPhotos(Long owner, List<Long> recordIds, int limit) {
        return travelPhotos.findRecordMapPhotos(owner, recordIds, PageRequest.of(0, limit)).stream().map(p ->
                new TravelPhoto(p.getRecord().getId(), new PhotoData(p.getId(), p.getOriginalFileName(), Origin.TRAVEL))).toList();
    }

    @Override public Map<Long, Long> collectionCounts(Long owner) {
        return places.countCollections(owner).stream()
                .collect(Collectors.toMap(RecordPlaceRepository.CollectionCount::getCollectionId, RecordPlaceRepository.CollectionCount::getTotal));
    }

    @Override public Set<Long> sharedCollections(Long owner, LocalDateTime now) {
        return new HashSet<>(shares.findActiveCollectionIds(owner, now));
    }

    @Override public List<CollectionData> collections(Long owner) {
        return collections.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner).stream().map(this::collection).toList();
    }

    @Override public CollectionData collection(Long owner, Long id) {
        return collection(collections.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable));
    }

    @Override public CandidatePage travelCandidates(Long owner, Window window) {
        var result = travelRecords.findRecordMapCandidates(owner, PageRequest.of(window.page(), window.size()));
        return new CandidatePage(result.getContent().stream().map(r -> new TravelCandidateData(r.getId(), r.getPlan().getId(),
                r.getPlan().getName(), r.getTitle(), r.getExpenseDate(), r.getCountry(), r.getRegion(), r.getLatitude().doubleValue(),
                r.getLongitude().doubleValue())).toList(), window, result.getTotalElements());
    }

    private VisitData visit(PlaceVisit visit) {
        return new VisitData(visit.getId(), visit.getPlace().getId(), visit.getTitle(), visit.getMemo(), visit.getVisitDate(), visit.getVisitTime());
    }

    private CollectionData collection(PlaceCollection collection) {
        return new CollectionData(collection.getId(), collection.getName(), collection.getMemo(), collection.getColorHex());
    }

    static String searchPattern(String keyword) {
        return keyword.isEmpty() ? null : "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
