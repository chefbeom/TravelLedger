package com.playdata.calen.calendar;

import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.dto.RecordMapDtos.PlaceView;
import com.playdata.calen.recordmap.dto.RecordMapDtos.VisitView;
import com.playdata.calen.recordmap.dto.RecordMapDtos.PhotoView;
import com.playdata.calen.recordmap.domain.PlaceVisit;
import com.playdata.calen.recordmap.domain.RecordPlace;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class CalendarDayService {
    private final EntityManager entities;
    private final LedgerEntryService ledger;

    public record Place(Long id, Long placeId, String placeName, String title, LocalTime time, String memo,
                        List<String> categories, long photoCount, Long thumbnailPhotoId, Long sourceTravelRecordId,
                        String sourceKind, Long travelPlanId, Long thumbnailMediaId) { }
    public record TravelRecord(Long id, Long planId, String planName, String title, String type, LocalTime time, String memo,
                               String placeName, BigDecimal amountKrw, String currencyCode, BigDecimal amount,
                               boolean linkedToLedger, Long importedPlaceId, long photoCount, Long thumbnailMediaId) { }
    public record Route(Long id, Long planId, String planName, String title, String transportMode, BigDecimal distanceKm, Integer durationMinutes) { }
    public record PlaceSource(PlaceView place, VisitView visit) { }

    public PlaceSource placeSource(Long owner, Long placeId, Long visitId) {
        RecordPlace place = entities.createQuery("select p from RecordPlace p where p.ownerId = :owner and p.id = :id", RecordPlace.class)
                .setParameter("owner", owner).setParameter("id", placeId).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("장소를 찾을 수 없습니다."));
        long count = entities.createQuery("select count(v.id) from PlaceVisit v where v.ownerId = :owner and v.place.id = :place", Long.class)
                .setParameter("owner", owner).setParameter("place", placeId).getSingleResult();
        if (place.getSourceTravelRecordId() != null && entities.createQuery("select count(r.id) from TravelExpenseRecord r where r.id = :id and r.plan.owner.id = :owner", Long.class)
                .setParameter("id", place.getSourceTravelRecordId()).setParameter("owner", owner).getSingleResult() > 0) count++;
        PlaceView view = new PlaceView(place.getId(), place.getName(), place.getMemo(), place.getCountry(), place.getRegion(),
                place.getLatitude(), place.getLongitude(), place.isManuallyVisited() || count > 0, count, place.getSourceTravelRecordId(),
                place.getCollections().stream().filter(c -> owner.equals(c.getOwnerId())).map(c -> c.getId()).toList());
        if (visitId == null) return new PlaceSource(view, null);
        PlaceVisit visit = entities.createQuery("select v from PlaceVisit v where v.id = :id and v.ownerId = :owner and v.place.id = :place", PlaceVisit.class)
                .setParameter("id", visitId).setParameter("owner", owner).setParameter("place", placeId).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("방문 기록을 찾을 수 없습니다."));
        List<PhotoView> photos = entities.createQuery("select p.id, p.originalName from PlacePhoto p where p.ownerId = :owner and p.visit.id = :visit order by p.id", Object[].class)
                .setParameter("owner", owner).setParameter("visit", visitId).getResultList().stream()
                .map(row -> new PhotoView("photo:" + row[0], (String) row[1], "/api/record-map/photos/" + row[0] + "/content")).toList();
        return new PlaceSource(view, new VisitView("visit:" + visit.getId(), placeId, visit.getTitle(), visit.getMemo(), visit.getVisitDate(), visit.getVisitTime(), null, null, photos));
    }

    public List<LedgerEntryResponse> ledger(Long owner, LocalDate date) { return ledger.getEntries(owner, date, date); }

    public List<Place> places(Long owner, LocalDate date, Long collectionId) {
        return places(owner, date, collectionId, null);
    }

    public List<Place> places(Long owner, LocalDate date, Long collectionId, Long planId) {
        List<PlaceVisit> visits = planId == null ? entities.createQuery("""
                select v from PlaceVisit v join fetch v.place p where v.ownerId = :owner and p.ownerId = :owner and v.visitDate = :date
                  and (:collection is null or exists (select c.id from RecordPlace linked join linked.collections c where linked.id = p.id and c.ownerId = :owner and c.id = :collection))
                order by v.visitTime, v.id
                """, PlaceVisit.class).setParameter("owner", owner).setParameter("date", date).setParameter("collection", collectionId).getResultList() : List.of();
        List<Long> ids = visits.stream().map(PlaceVisit::getId).toList();
        Map<Long, Object[]> photos = new HashMap<>();
        if (!ids.isEmpty()) for (Object[] row : entities.createQuery("select p.visit.id, count(p.id), min(p.id) from PlacePhoto p where p.ownerId = :owner and p.visit.id in :ids group by p.visit.id", Object[].class)
                .setParameter("owner", owner).setParameter("ids", ids).getResultList()) photos.put((Long) row[0], row);
        List<Place> result = new ArrayList<>(visits.stream().map(v -> {
            Object[] media = photos.get(v.getId());
            return new Place(v.getId(), v.getPlace().getId(), v.getPlace().getName(), v.getTitle(), v.getVisitTime(), v.getMemo(),
                    v.getPlace().getCollections().stream().filter(c -> owner.equals(c.getOwnerId())).map(c -> c.getName()).toList(),
                    media == null ? 0 : ((Number) media[1]).longValue(), media == null ? null : (Long) media[2], v.getPlace().getSourceTravelRecordId(), "VISIT", null, null);
        }).toList());
        List<Object[]> imported = entities.createQuery("""
                select p, r from RecordPlace p, TravelExpenseRecord r
                where p.ownerId = :owner and r.plan.owner.id = :owner and p.sourceTravelRecordId = r.id and r.expenseDate = :date and (:plan is null or r.plan.id = :plan)
                  and (:collection is null or exists (select c.id from RecordPlace linked join linked.collections c where linked.id = p.id and c.ownerId = :owner and c.id = :collection))
                order by r.expenseTime, r.id
                """, Object[].class).setParameter("owner", owner).setParameter("date", date).setParameter("collection", collectionId).setParameter("plan", planId).getResultList();
        if (!imported.isEmpty()) {
            List<Long> recordIds = imported.stream().map(row -> ((TravelExpenseRecord) row[1]).getId()).toList();
            Map<Long, Object[]> media = new HashMap<>();
            for (Object[] row : entities.createQuery("select m.record.id, count(m.id), min(m.id) from TravelMediaAsset m where m.plan.owner.id = :owner and m.mediaType = com.playdata.calen.travel.domain.TravelMediaType.PHOTO and m.record.id in :ids group by m.record.id", Object[].class)
                    .setParameter("owner", owner).setParameter("ids", recordIds).getResultList()) media.put((Long) row[0], row);
            for (Object[] row : imported) {
                RecordPlace place = (RecordPlace) row[0]; TravelExpenseRecord record = (TravelExpenseRecord) row[1]; Object[] image = media.get(record.getId());
                result.add(new Place(record.getId(), place.getId(), place.getName(), record.getTitle(), record.getExpenseTime(), record.getMemo(),
                        place.getCollections().stream().filter(c -> owner.equals(c.getOwnerId())).map(c -> c.getName()).toList(),
                        image == null ? 0 : ((Number) image[1]).longValue(), null, record.getId(), "TRAVEL_IMPORT", record.getPlan().getId(), image == null ? null : (Long) image[2]));
            }
        }
        return result;
    }

    public List<TravelRecord> travelRecords(Long owner, LocalDate date, Long planId) {
        List<TravelExpenseRecord> records = entities.createQuery("""
                select r from TravelExpenseRecord r join fetch r.plan p
                where p.owner.id = :owner and r.expenseDate = :date and (:plan is null or p.id = :plan) order by r.expenseTime, r.id
                """, TravelExpenseRecord.class).setParameter("owner", owner).setParameter("date", date).setParameter("plan", planId).getResultList();
        if (records.isEmpty()) return List.of();
        List<Long> ids = records.stream().map(TravelExpenseRecord::getId).toList();
        var linked = entities.createQuery("select distinct e.travelRecordId from LedgerEntry e where e.owner.id = :owner and e.deletedAt is null and e.travelRecordId in :ids", Long.class)
                .setParameter("owner", owner).setParameter("ids", ids).getResultList();
        Map<Long, Long> imported = new HashMap<>();
        for (Object[] row : entities.createQuery("select p.sourceTravelRecordId, p.id from RecordPlace p where p.ownerId = :owner and p.sourceTravelRecordId in :ids", Object[].class)
                .setParameter("owner", owner).setParameter("ids", ids).getResultList()) imported.put((Long) row[0], (Long) row[1]);
        Map<Long, Object[]> media = new HashMap<>();
        for (Object[] row : entities.createQuery("select m.record.id, count(m.id), min(m.id) from TravelMediaAsset m where m.plan.owner.id = :owner and m.mediaType = com.playdata.calen.travel.domain.TravelMediaType.PHOTO and m.record.id in :ids group by m.record.id", Object[].class)
                .setParameter("owner", owner).setParameter("ids", ids).getResultList()) media.put((Long) row[0], row);
        return records.stream().map(r -> {
            Object[] image = media.get(r.getId());
            return new TravelRecord(r.getId(), r.getPlan().getId(), r.getPlan().getName(), r.getTitle(), r.getRecordType() == null ? "LEDGER" : r.getRecordType().name(), r.getExpenseTime(), r.getMemo(),
                    r.getPlaceName(), r.getAmountKrw(), r.getCurrencyCode(), r.getAmount(), linked.contains(r.getId()), imported.get(r.getId()),
                    image == null ? 0 : ((Number) image[1]).longValue(), image == null ? null : (Long) image[2]);
        }).toList();
    }

    public List<Route> routes(Long owner, LocalDate date, Long planId) {
        List<Object[]> rows = entities.createQuery("""
                select r.id, p.id, p.name, r.title, r.transportMode, r.distanceKm, r.durationMinutes
                from TravelRouteSegment r join r.plan p where p.owner.id = :owner and r.routeDate = :date and (:plan is null or p.id = :plan) order by r.id
                """, Object[].class).setParameter("owner", owner).setParameter("date", date).setParameter("plan", planId).getResultList();
        return rows.stream().map(row -> new Route((Long) row[0], (Long) row[1], (String) row[2], (String) row[3], row[4] == null ? null : row[4].toString(), (BigDecimal) row[5], (Integer) row[6])).toList();
    }
}
