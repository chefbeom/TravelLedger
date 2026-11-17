package com.playdata.calen.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.UserLayoutSettingService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.application.port.out.TravelExhibitReadStore;
import com.playdata.calen.travel.domain.policy.TravelExhibitPolicy;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.domain.TravelMediaType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Selection stores only share IDs. Every detail/summary resolves current permission again. */
@Service @RequiredArgsConstructor
public class CalendarSharedService {
    private static final String SCOPE = "integrated-calendar-shared-selections";
    private static final Set<String> TYPES = Set.of("exhibit", "record");
    private final UserLayoutSettingService layouts;
    private final AppUserRepository users;
    private final ObjectMapper mapper;
    private final TravelExhibitReadStore exhibitStore;
    private final RecordSharingService records;
    private final TravelPlanRepository plans;
    private final CalendarSummaryService summary;
    private final EntityManager entities;

    public record Option(Long id, String title, String owner, boolean selected) { }
    public record OptionPage(List<Option> items, int page, int totalPages) { }
    public record SharedSummary(String type, Long shareId, Long planId, String title, CalendarSummaryService.TravelSummary data) { }
    public record SharedRecord(Long id, String title, String kind, LocalTime time, String memo, String placeName, BigDecimal amountKrw, long photoCount, Long thumbnailMediaId) { }
    public record SharedRoute(Long id, String title, String transportMode, BigDecimal distanceKm) { }
    public record SharedDay(String type, Long shareId, Long planId, String title, List<SharedRecord> records, List<SharedRoute> routes) { }
    private record Access(Long ownerId, Long planId) { }

    @Transactional(readOnly = true)
    public OptionPage options(Long viewer, String type, int page) {
        validateType(type); int safePage = Math.max(0, Math.min(page, 100000));
        JsonNode selected = selections(viewer).path(type);
        if ("exhibit".equals(type)) {
            String criteria = "from TravelPlanShare s where s.recipient.id = :viewer and s.plan.status = :status and s.plan.owner.active = true and s.sharedBy.id = s.plan.owner.id";
            var items = entities.createQuery("select s.id, s.plan.name, s.sharedBy.displayName " + criteria + " order by s.createdAt desc, s.id desc", Object[].class)
                    .setParameter("viewer", viewer).setParameter("status", TravelPlanStatus.COMPLETED).setFirstResult(safePage * 20).setMaxResults(20).getResultList();
            long total = entities.createQuery("select count(s.id) " + criteria, Long.class).setParameter("viewer", viewer).setParameter("status", TravelPlanStatus.COMPLETED).getSingleResult();
            return new OptionPage(items.stream().map(item -> new Option((Long) item[0], (String) item[1], (String) item[2], contains(selected, (Long) item[0]))).toList(), safePage, (int) ((total + 19) / 20));
        }
        var result = records.list(viewer, RecordShareKind.TRAVEL, false, RecordShareStatus.ACCEPTED, safePage, 20);
        return new OptionPage(result.items().stream().map(item -> new Option(item.id(), item.title(), item.senderName(), contains(selected, item.id()))).toList(), result.page(), result.totalPages());
    }

    @Transactional
    public void setSelected(Long viewer, String type, Long shareId, boolean selected) {
        validateType(type);
        if (shareId == null || shareId < 1) throw new BadRequestException("공유 항목을 확인해 주세요.");
        if (selected) access(viewer, type, shareId);
        users.findActiveForPreferenceUpdate(viewer).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        ObjectNode state = selections(viewer);
        var next = mapper.createArrayNode();
        for (JsonNode value : state.path(type)) if (value.canConvertToLong() && value.asLong() != shareId) next.add(value.asLong());
        if (selected) next.add(shareId);
        state.set(type, next);
        layouts.saveLayoutSetting(viewer, SCOPE, new UserLayoutSettingRequest(1, state));
    }

    @Transactional
    public SharedSummary summary(Long viewer, String type, Long shareId, LocalDate from, LocalDate to) {
        validateType(type);
        if (!contains(selections(viewer).path(type), shareId)) throw new NotFoundException("선택한 공유 자료를 찾을 수 없습니다.");
        Access access = access(viewer, type, shareId);
        var plan = plans.findByIdAndOwnerId(access.planId(), access.ownerId()).orElseThrow(() -> new NotFoundException("공유 여행을 찾을 수 없습니다."));
        var data = summary.travel(access.ownerId(), from, to, plan.getId());
        // A travel share does not reveal whether the owner also imported a record
        // into a private map collection.
        var safeData = new CalendarSummaryService.TravelSummary(data.periods(), data.routes(), data.records().stream()
                .map(item -> new CalendarSummaryService.RecordDay(item.date(), item.type(), item.count(), 0)).toList());
        return new SharedSummary(type, shareId, plan.getId(), plan.getName(), safeData);
    }

    @Transactional
    public SharedDay day(Long viewer, String type, Long shareId, LocalDate date) {
        validateType(type);
        if (date == null || !contains(selections(viewer).path(type), shareId)) throw new NotFoundException("선택한 공유 자료를 찾을 수 없습니다.");
        Access access = access(viewer, type, shareId);
        var plan = plans.findByIdAndOwnerId(access.planId(), access.ownerId()).orElseThrow(() -> new NotFoundException("공유 여행을 찾을 수 없습니다."));
        var records = entities.createQuery("""
                select r.id, r.title, r.recordType, r.expenseTime, r.memo, r.placeName, r.amountKrw
                from TravelExpenseRecord r where r.plan.id = :plan and r.plan.owner.id = :owner and r.expenseDate = :date order by r.expenseTime, r.id
                """, Object[].class).setParameter("plan", plan.getId()).setParameter("owner", access.ownerId()).setParameter("date", date).getResultList();
        Map<Long, Object[]> media = new HashMap<>();
        if (!records.isEmpty()) {
            List<Long> ids = records.stream().map(row -> (Long) row[0]).toList();
            for (Object[] row : entities.createQuery("select m.record.id, count(m.id), min(m.id) from TravelMediaAsset m where m.plan.id = :plan and m.record.id in :ids and m.mediaType = :photo group by m.record.id", Object[].class)
                    .setParameter("plan", plan.getId()).setParameter("ids", ids).setParameter("photo", TravelMediaType.PHOTO).getResultList()) media.put((Long) row[0], row);
        }
        var routes = entities.createQuery("select r.id, r.title, r.transportMode, r.distanceKm from TravelRouteSegment r where r.plan.id = :plan and r.plan.owner.id = :owner and r.routeDate = :date order by r.id", Object[].class)
                .setParameter("plan", plan.getId()).setParameter("owner", access.ownerId()).setParameter("date", date).getResultList();
        return new SharedDay(type, shareId, plan.getId(), plan.getName(), records.stream().map(row -> {
            Object[] image = media.get((Long) row[0]);
            return new SharedRecord((Long) row[0], (String) row[1], row[2] == null ? "LEDGER" : row[2].toString(), (LocalTime) row[3], (String) row[4], (String) row[5], (BigDecimal) row[6], image == null ? 0 : ((Number) image[1]).longValue(), image == null ? null : (Long) image[2]);
        }).toList(), routes.stream().map(row -> new SharedRoute((Long) row[0], (String) row[1], row[2] == null ? null : row[2].toString(), (BigDecimal) row[3])).toList());
    }

    private Access access(Long viewer, String type, Long shareId) {
        if ("exhibit".equals(type)) {
            exhibitStore.requireActiveViewer(viewer);
            var access = exhibitStore.receivedShare(viewer, shareId);
            if (!TravelExhibitPolicy.completed(access.plan().status())) throw new NotFoundException("공유 여행을 찾을 수 없습니다.");
            return new Access(access.plan().ownerId(), access.plan().id());
        }
        var access = records.calendarTravelAccess(viewer, shareId);
        plans.findByIdAndOwnerId(access.planId(), access.ownerId()).orElseThrow(() -> new NotFoundException("공유 여행을 찾을 수 없습니다."));
        return new Access(access.ownerId(), access.planId());
    }

    private ObjectNode selections(Long owner) {
        JsonNode value = layouts.getLayoutSetting(owner, SCOPE).payload();
        return value instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
    }
    private static boolean contains(JsonNode array, Long id) {
        if (id == null || !array.isArray()) return false;
        for (JsonNode item : array) if (item.isIntegralNumber() && item.canConvertToLong() && item.asLong() == id) return true;
        return false;
    }
    private static void validateType(String type) {
        if (!TYPES.contains(type)) throw new BadRequestException("공유 자료 종류를 확인해 주세요.");
    }
}
