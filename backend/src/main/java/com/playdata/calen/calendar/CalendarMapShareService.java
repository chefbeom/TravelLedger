package com.playdata.calen.calendar;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.domain.RecordMapShare;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Scope;
import com.playdata.calen.recordmap.dto.RecordMapDtos.PlaceView;
import com.playdata.calen.recordmap.dto.RecordMapDtos.VisitView;
import com.playdata.calen.recordmap.dto.RecordMapDtos.PhotoView;
import com.playdata.calen.recordmap.repository.RecordMapShareRepository;
import com.playdata.calen.recordmap.repository.RecordPlaceRepository;
import com.playdata.calen.recordmap.repository.PlacePhotoRepository;
import com.playdata.calen.recordmap.service.RecordMapMediaService;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Link possession is proven once and persisted without persisting the bearer token. */
@Service @RequiredArgsConstructor
public class CalendarMapShareService {
    private final CalendarMapGrantRepository grants;
    private final RecordMapShareRepository shares;
    private final RecordPlaceRepository places;
    private final PlacePhotoRepository photos;
    private final TravelMediaAssetRepository travelPhotos;
    private final RecordMapShareService originalShares;
    private final AppUserRepository users;
    private final CalendarSummaryService summaries;
    private final CalendarDayService days;
    private final EntityManager entities;

    public record Option(Long id, String title, boolean selected, boolean includesVisits) { }
    public record OptionPage(List<Option> items, int page, int totalPages) { }
    public record Summary(Long id, String title, List<CalendarSummaryService.CountDay> days) { }
    public record Entry(Long id, Long placeId, String sourceKind, String placeName, String title, LocalTime time, String memo, long photoCount, Long thumbnailId) { }
    public record Day(Long id, String title, List<Entry> entries) { }

    @Transactional public void add(Long viewer, String token) {
        RecordMapShare share = originalShares.resolve(token);
        var user = users.findActiveForPreferenceUpdate(viewer).orElseThrow(RecordMapShareService::unavailable);
        validateShare(share, share.getOwnerId());
        var grant = grants.findByViewerIdAndShareId(viewer, share.getId()).orElseGet(CalendarMapGrant::new);
        grant.setViewer(user); grant.setShareId(share.getId()); grant.setShareOwnerId(share.getOwnerId()); grant.setSelected(true);
        grants.saveAndFlush(grant);
    }

    @Transactional public void select(Long viewer, Long id, boolean selected) {
        users.findActiveForPreferenceUpdate(viewer).orElseThrow(RecordMapShareService::unavailable);
        var grant = grants.findByIdAndViewerId(id, viewer).orElseThrow(RecordMapShareService::unavailable);
        if (selected) resolve(grant);
        grant.setSelected(selected);
    }

    @Transactional(readOnly = true) public OptionPage options(Long viewer, int page) {
        if (!users.existsByIdAndActiveTrue(viewer)) throw RecordMapShareService.unavailable();
        int safePage = Math.max(0, Math.min(100000, page));
        String where = " from CalendarMapGrant g, RecordMapShare s where g.viewer.id = :viewer and g.shareId = s.id and g.shareOwnerId = s.ownerId and s.collection.ownerId = s.ownerId and s.active = true and s.expiresAt > :now and exists (select u.id from AppUser u where u.id = s.ownerId and u.active = true)";
        var rows = entities.createQuery("select g.id, s.collection.name, g.selected, s.includeVisits" + where + " order by g.id desc", Object[].class)
                .setParameter("viewer", viewer).setParameter("now", LocalDateTime.now()).setFirstResult(safePage * 20).setMaxResults(20).getResultList();
        long total = entities.createQuery("select count(g.id)" + where, Long.class).setParameter("viewer", viewer).setParameter("now", LocalDateTime.now()).getSingleResult();
        return new OptionPage(rows.stream().map(r -> new Option((Long) r[0], (String) r[1], (Boolean) r[2], (Boolean) r[3])).toList(), safePage, (int) ((total + 19) / 20));
    }

    @Transactional(readOnly = true) public Summary summary(Long viewer, Long id, LocalDate from, LocalDate to) {
        var share = selectedShare(viewer, id);
        return new Summary(id, share.getCollection().getName(), share.isIncludeVisits()
                ? summaries.places(share.getOwnerId(), from, to, share.getCollection().getId()) : List.of());
    }

    @Transactional(readOnly = true) public Day day(Long viewer, Long id, LocalDate date) {
        if (date == null) throw RecordMapShareService.unavailable();
        var share = selectedShare(viewer, id);
        var entries = share.isIncludeVisits() ? days.places(share.getOwnerId(), date, share.getCollection().getId()).stream()
                .map(item -> new Entry(item.id(), item.placeId(), item.sourceKind(), item.placeName(), item.title(), item.time(), item.memo(), item.photoCount(),
                        "VISIT".equals(item.sourceKind()) ? item.thumbnailPhotoId() : item.thumbnailMediaId())).toList() : List.<Entry>of();
        return new Day(id, share.getCollection().getName(), entries);
    }

    @Transactional(readOnly = true) public Scope scope(Long viewer, Long id) {
        var share = selectedShare(viewer, id);
        return new Scope(share.getOwnerId(), share.getCollection().getId(), true, share.isIncludeVisits());
    }

    @Transactional(readOnly = true) public CalendarDayService.PlaceSource placeSource(Long viewer, Long id, Long placeId, String kind, Long recordId, LocalDate date) {
        var share = selectedShare(viewer, id);
        var place = places.findByIdAndOwnerId(placeId, share.getOwnerId()).orElseThrow(RecordMapShareService::unavailable);
        originalShares.requireMediaScope(share, place);
        boolean visitKind = "VISIT".equals(kind);
        if (recordId == null || (!visitKind && !"TRAVEL_IMPORT".equals(kind))) throw RecordMapShareService.unavailable();
        var source = days.placeSource(share.getOwnerId(), placeId, visitKind ? recordId : null);
        var originalPlace = source.place();
        var safePlace = new PlaceView(originalPlace.id(), originalPlace.name(), originalPlace.memo(), originalPlace.country(), originalPlace.region(),
                originalPlace.latitude(), originalPlace.longitude(), originalPlace.visited(), originalPlace.visitCount(), null, List.of(share.getCollection().getId()));
        if (visitKind) {
            var v = source.visit();
            var images = v.photos().stream().map(p -> new PhotoView(p.id(), p.name(), "/api/integrated-calendar/shared-map/" + id + "/photos/visit/" + p.id().substring("photo:".length()))).toList();
            return new CalendarDayService.PlaceSource(safePlace, new VisitView(v.id(), v.placeId(), v.title(), v.memo(), v.date(), v.time(), null, null, images));
        }
        if (date == null || !Objects.equals(place.getSourceTravelRecordId(), recordId)) throw RecordMapShareService.unavailable();
        var item = days.places(share.getOwnerId(), date, share.getCollection().getId()).stream()
                .filter(p -> "TRAVEL_IMPORT".equals(p.sourceKind()) && Objects.equals(p.id(), recordId) && Objects.equals(p.placeId(), placeId))
                .findFirst().orElseThrow(RecordMapShareService::unavailable);
        var images = entities.createQuery("select m.id, m.originalFileName from TravelMediaAsset m where m.record.id = :record and m.plan.owner.id = :owner and m.contentType like 'image/%' order by m.id", Object[].class)
                .setParameter("record", recordId).setParameter("owner", share.getOwnerId()).getResultList().stream()
                .map(row -> new PhotoView("travel-photo:" + row[0], (String) row[1], "/api/integrated-calendar/shared-map/" + id + "/photos/travel/" + row[0])).toList();
        return new CalendarDayService.PlaceSource(safePlace, new VisitView("travel:" + recordId, placeId, item.title(), item.memo(), date, item.time(), null, null, images));
    }

    @Transactional(readOnly = true) public RecordMapMediaService.Media photo(Long viewer, Long id, String kind, Long photoId) {
        var share = selectedShare(viewer, id);
        if ("visit".equals(kind)) {
            var photo = photos.findByIdAndOwnerId(photoId, share.getOwnerId()).orElseThrow(RecordMapShareService::unavailable);
            originalShares.requireMediaScope(share, photo.getVisit().getPlace());
            return new RecordMapMediaService.Media(photo.getStoragePath(), photo.getContentType(), photo.getOriginalName());
        }
        if ("travel".equals(kind)) {
            var photo = travelPhotos.findById(photoId).filter(p -> Objects.equals(p.getPlan().getOwner().getId(), share.getOwnerId()))
                    .filter(p -> p.getContentType().startsWith("image/")).orElseThrow(RecordMapShareService::unavailable);
            var place = places.findByOwnerIdAndSourceTravelRecordId(share.getOwnerId(), photo.getRecord().getId()).orElseThrow(RecordMapShareService::unavailable);
            originalShares.requireMediaScope(share, place);
            return new RecordMapMediaService.Media(photo.getStoragePath(), photo.getContentType(), photo.getOriginalFileName());
        }
        throw RecordMapShareService.unavailable();
    }

    private RecordMapShare selectedShare(Long viewer, Long id) {
        if (!users.existsByIdAndActiveTrue(viewer)) throw RecordMapShareService.unavailable();
        var grant = grants.findByIdAndViewerId(id, viewer).filter(CalendarMapGrant::isSelected).orElseThrow(RecordMapShareService::unavailable);
        return resolve(grant);
    }
    private RecordMapShare resolve(CalendarMapGrant grant) {
        var share = shares.findById(grant.getShareId()).orElseThrow(RecordMapShareService::unavailable);
        validateShare(share, grant.getShareOwnerId());
        return share;
    }
    private void validateShare(RecordMapShare share, Long owner) {
        if (!share.isActive() || !share.getExpiresAt().isAfter(LocalDateTime.now()) || !Objects.equals(share.getOwnerId(), owner)
                || share.getCollection() == null || !Objects.equals(share.getCollection().getOwnerId(), owner)
                || !users.existsByIdAndActiveTrue(owner)) throw RecordMapShareService.unavailable();
    }
}
