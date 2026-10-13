package com.playdata.calen.recordmap.service;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.recordmap.domain.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.repository.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RecordMapService {
    private final RecordPlaceRepository places;
    private final PlaceCollectionRepository collections;
    private final PlaceVisitRepository visits;
    private final PlacePhotoRepository photos;
    private final RecordMapShareRepository shares;
    private final RecordMapShareService sharing;
    private final RecordMapMediaService media;
    private final AppUserService users;
    private final TravelExpenseRecordRepository travelRecords;
    private final TravelMediaAssetRepository travelPhotos;

    public Overview overview(Long owner, Long collectionId, String state, LocalDate from, LocalDate to, int page) {
        users.getRequiredUser(owner);
        if (collectionId != null) ownedCollection(owner, collectionId);
        return buildOverview(owner, collectionId, state, from, to, page, null, true);
    }

    public Overview sharedOverview(String token, String state, LocalDate from, LocalDate to, int page) {
        RecordMapShare share = sharing.resolve(token);
        if (!share.isIncludeVisits() && (from != null || to != null)) throw new BadRequestException("방문 기록을 공유하지 않은 지도에서는 날짜를 조회할 수 없습니다.");
        return buildOverview(share.getOwnerId(), share.getCollection().getId(), state, from, to, page, token, share.isIncludeVisits());
    }

    private Overview buildOverview(Long owner, Long collectionId, String state, LocalDate from, LocalDate to,
            int page, String token, boolean includeVisits) {
        if (!Set.of("ALL", "WANTED", "VISITED").contains(state)) throw new BadRequestException("방문 상태를 확인해 주세요.");
        if (from != null && to != null && from.isAfter(to)) throw new BadRequestException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        if (page < 0) throw new BadRequestException("페이지를 확인해 주세요.");
        var result = places.findVisible(owner, collectionId, state, from, to,
            PageRequest.of(page, 100, Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        List<RecordPlace> entries = result.getContent();
        List<Long> ids = entries.stream().map(RecordPlace::getId).toList();
        Map<Long, Long> counts = ids.isEmpty() ? Map.of() : visits.countVisits(owner, ids).stream()
            .collect(Collectors.toMap(PlaceVisitRepository.VisitCount::getPlaceId, PlaceVisitRepository.VisitCount::getTotal));
        List<PlaceVisit> rows = !includeVisits || ids.isEmpty() ? List.of()
            : visits.findVisibleVisits(owner, ids, from, to, PageRequest.of(0, 201));
        boolean limited = rows.size() > 200;
        rows = rows.stream().filter(v -> inRange(v.getVisitDate(), from, to)).limit(200).toList();
        List<Long> visitIds = rows.stream().map(PlaceVisit::getId).toList();
        Map<Long, List<PlacePhoto>> grouped = visitIds.isEmpty() ? Map.of() :
            photos.findAllByOwnerIdAndVisitIdInOrderByIdAsc(owner, visitIds).stream().collect(Collectors.groupingBy(p -> p.getVisit().getId()));
        List<VisitView> visitViews = new ArrayList<>();
        for (PlaceVisit v : rows) {
            visitViews.add(visitView(v, grouped.getOrDefault(v.getId(), List.of()), token));
        }
        List<Long> sources = entries.stream().map(RecordPlace::getSourceTravelRecordId).filter(Objects::nonNull).toList();
        Map<Long, TravelExpenseRecord> originals = new HashMap<>();
        if (!sources.isEmpty()) travelRecords.findAllByIdInAndPlanOwnerId(sources, owner).forEach(r -> originals.put(r.getId(), r));
        if (includeVisits && !sources.isEmpty()) {
            var assets = travelPhotos.findRecordMapPhotos(owner, sources, PageRequest.of(0, 1000));
            limited |= assets.size() == 1000;
            var byRecord = assets.stream().collect(Collectors.groupingBy(p -> p.getRecord().getId()));
            for (RecordPlace place : entries) {
                var r = originals.get(place.getSourceTravelRecordId());
                if (r == null || !inRange(r.getExpenseDate(), from, to)) continue;
                List<PhotoView> pictures = byRecord.getOrDefault(r.getId(), List.of()).stream().map(p -> new PhotoView(
                    "travel-photo:" + p.getId(), p.getOriginalFileName(), token == null
                        ? "/api/travel/media/" + p.getId() + "/content"
                        : "/api/record-map/public/" + token + "/travel-photos/" + p.getId() + "/content")).toList();
                visitViews.add(new VisitView("travel:" + r.getId(), place.getId(), r.getTitle(), r.getMemo(),
                    r.getExpenseDate(), r.getExpenseTime(), r.getPlan().getId(), r.getPlan().getName(), pictures));
            }
        }
        List<PlaceView> placeViews = entries.stream().map(p -> {
            long count = counts.getOrDefault(p.getId(), 0L) + (originals.containsKey(p.getSourceTravelRecordId()) ? 1 : 0);
            return new PlaceView(p.getId(), p.getName(), token == null || includeVisits ? p.getMemo() : null,
                p.getCountry(), p.getRegion(), p.getLatitude(), p.getLongitude(), p.isManuallyVisited() || count > 0,
                includeVisits ? count : 0, token == null ? p.getSourceTravelRecordId() : null,
                p.getCollections().stream().map(PlaceCollection::getId).filter(id -> token == null || id.equals(collectionId)).toList());
        }).toList();
        List<CollectionView> collectionViews = token == null
            ? collections.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner).stream().map(this::collectionView).toList()
            : List.of(new CollectionView(collectionId, ownedCollection(owner, collectionId).getName(), null, ownedCollection(owner, collectionId).getColorHex()));
        return new Overview(collectionViews, placeViews, visitViews, result.getTotalElements(), page,
            result.getTotalPages(), limited, token != null, includeVisits, token == null ? "기록 지도" : collectionViews.get(0).name());
    }

    public List<VisitView> placeVisits(Long owner, Long placeId, int page) {
        ownedPlace(owner, placeId);
        List<PlaceVisit> rows = visits.findAllByOwnerIdAndPlaceIdInOrderByVisitDateDescVisitTimeDescIdDesc(owner,
            List.of(placeId), PageRequest.of(Math.max(0, page), 20));
        List<Long> ids = rows.stream().map(PlaceVisit::getId).toList();
        var grouped = ids.isEmpty() ? Map.<Long, List<PlacePhoto>>of() : photos.findAllByOwnerIdAndVisitIdInOrderByIdAsc(owner, ids)
            .stream().collect(Collectors.groupingBy(p -> p.getVisit().getId()));
        return rows.stream().map(v -> visitView(v, grouped.getOrDefault(v.getId(), List.of()), null)).toList();
    }

    @Transactional
    public Long savePlace(Long owner, Long id, PlaceRequest request) {
        users.getRequiredUser(owner);
        if (!Double.isFinite(request.latitude()) || !Double.isFinite(request.longitude())) throw new BadRequestException("위치 좌표가 올바르지 않습니다.");
        RecordPlace place = id == null ? new RecordPlace() : ownedPlace(owner, id);
        place.setOwnerId(owner); place.setName(request.name().trim()); place.setMemo(request.memo());
        place.setCountry(request.country()); place.setRegion(request.region()); place.setLatitude(request.latitude());
        place.setLongitude(request.longitude()); place.setManuallyVisited(request.visited());
        place.setCollections(resolveCollections(owner, request.collectionIds())); place.setUpdatedAt(LocalDateTime.now());
        places.saveAndFlush(place);
        return place.getId();
    }

    @Transactional
    public void deletePlace(Long owner, Long id) {
        RecordPlace place = ownedPlace(owner, id);
        if (visits.countByPlaceIdAndOwnerId(id, owner) > 0) throw new BadRequestException("방문 기록이 있는 장소는 삭제할 수 없습니다. 모음에서 제거하거나 방문 기록을 먼저 정리해 주세요.");
        places.delete(place);
    }

    @Transactional
    public CollectionView saveCollection(Long owner, Long id, CollectionRequest request) {
        users.getRequiredUser(owner);
        PlaceCollection c = id == null ? new PlaceCollection() : ownedCollection(owner, id);
        c.setOwnerId(owner); c.setName(request.name().trim()); c.setMemo(request.memo()); c.setColorHex(request.colorHex());
        return collectionView(collections.save(c));
    }

    @Transactional
    public void deleteCollection(Long owner, Long id) {
        PlaceCollection c = ownedCollection(owner, id);
        shares.deleteAll(shares.findAllByOwnerIdAndCollectionIdOrderByCreatedAtDesc(owner, id));
        shares.flush();
        places.detachCollection(id);
        collections.delete(c);
    }

    @Transactional
    public Long saveVisit(Long owner, Long placeId, Long id, VisitRequest request) {
        RecordPlace place = ownedPlace(owner, placeId);
        PlaceVisit v = id == null ? new PlaceVisit() : visits.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable);
        if (id != null && !Objects.equals(v.getPlace().getId(), placeId)) throw RecordMapShareService.unavailable();
        v.setOwnerId(owner); v.setPlace(place); v.setTitle(request.title().trim()); v.setMemo(request.memo());
        v.setVisitDate(request.date()); v.setVisitTime(request.time()); visits.saveAndFlush(v);
        place.setUpdatedAt(LocalDateTime.now());
        return v.getId();
    }

    @Transactional
    public void deleteVisit(Long owner, Long id) {
        PlaceVisit v = visits.lockOwned(id, owner).orElseThrow(RecordMapShareService::unavailable);
        media.deleteRows(photos.findAllByOwnerIdAndVisitIdInOrderByIdAsc(owner, List.of(id)));
        visits.delete(v);
    }

    public org.springframework.data.domain.Page<TravelCandidate> travelCandidates(Long owner, int page) {
        users.getRequiredUser(owner);
        return travelRecords.findRecordMapCandidates(owner, PageRequest.of(Math.max(0, page), 30)).map(r ->
            new TravelCandidate(r.getId(), r.getPlan().getId(), r.getPlan().getName(), r.getTitle(), r.getExpenseDate(),
                r.getCountry(), r.getRegion(), r.getLatitude().doubleValue(), r.getLongitude().doubleValue()));
    }

    @Transactional
    public Long importTravel(Long owner, TravelImportRequest request) {
        // Serialize imports of the same source before the uniqueness check.
        var original = travelRecords.lockRecordMapSource(request.recordId(), owner).orElseThrow(RecordMapShareService::unavailable);
        if (original.getLatitude() == null || original.getLongitude() == null) throw new BadRequestException("위치가 있는 여행 기록을 선택해 주세요.");
        double latitude = original.getLatitude().doubleValue(), longitude = original.getLongitude().doubleValue();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new BadRequestException("여행 원본의 위치 좌표를 먼저 확인해 주세요.");
        }
        RecordPlace p = places.findByOwnerIdAndSourceTravelRecordId(owner, original.getId()).orElseGet(RecordPlace::new);
        if (p.getId() == null) {
            p.setOwnerId(owner); p.setName(limit(original.getPlaceName() == null || original.getPlaceName().isBlank() ? original.getTitle() : original.getPlaceName(), 160));
            p.setCountry(limit(original.getCountry(), 80)); p.setRegion(limit(original.getRegion(), 120)); p.setLatitude(latitude);
            p.setLongitude(longitude); p.setSourceTravelRecordId(original.getId());
        }
        p.getCollections().addAll(resolveCollections(owner, request.collectionIds())); p.setUpdatedAt(LocalDateTime.now());
        places.saveAndFlush(p);
        return p.getId();
    }

    private RecordPlace ownedPlace(Long owner, Long id) { return places.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable); }
    private PlaceCollection ownedCollection(Long owner, Long id) { return collections.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable); }
    private Set<PlaceCollection> resolveCollections(Long owner, List<Long> ids) {
        Set<PlaceCollection> result = new LinkedHashSet<>();
        for (Long id : ids == null ? List.<Long>of() : ids) result.add(ownedCollection(owner, id));
        return result;
    }
    private CollectionView collectionView(PlaceCollection c) { return new CollectionView(c.getId(), c.getName(), c.getMemo(), c.getColorHex()); }
    private VisitView visitView(PlaceVisit v, List<PlacePhoto> items, String token) {
        return new VisitView("visit:" + v.getId(), v.getPlace().getId(), v.getTitle(), v.getMemo(), v.getVisitDate(), v.getVisitTime(),
            null, null, items.stream().map(p -> new PhotoView("photo:" + p.getId(), p.getOriginalName(),
                "/api/record-map/" + (token == null ? "" : "public/" + token + "/") + "photos/" + p.getId() + "/content")).toList());
    }
    private boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
    private String limit(String value, int length) { return value == null || value.length() <= length ? value : value.substring(0, length); }
}
