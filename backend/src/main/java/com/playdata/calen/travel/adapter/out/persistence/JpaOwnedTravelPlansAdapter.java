package com.playdata.calen.travel.adapter.out.persistence;

import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.*;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.repository.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaOwnedTravelPlansAdapter implements TravelOwnedPlansStore {
    private final AppUserService users;
    private final TravelPlanRepository plans;
    private final TravelBudgetItemRepository budgets;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    private final TravelRoutePayloadPort payload;
    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public List<Aggregate> aggregates(Long owner) {
        var owned = plans.findAllByOwnerIdOrderByStartDateDescIdDesc(owner);
        var b = budgets.summarizeByPlanOwnerId(owner).stream().collect(Collectors.toMap(TravelBudgetItemRepository.PlanBudgetAggregate::getPlanId, Function.identity()));
        var r = records.summarizeByPlanOwnerId(owner, TravelRecordType.LEDGER, TravelRecordType.MEMORY).stream().collect(Collectors.toMap(TravelExpenseRecordRepository.PlanRecordAggregate::getPlanId, Function.identity()));
        var m = media.summarizeByPlanOwnerId(owner).stream().collect(Collectors.toMap(TravelMediaAssetRepository.PlanMediaAggregate::getPlanId, Function.identity()));
        var p = routes.summarizeByPlanOwnerId(owner).stream().collect(Collectors.toMap(TravelRouteSegmentRepository.PlanRouteAggregate::getPlanId, Function.identity()));
        return owned.stream().map(plan -> {
            var id = plan.getId(); var budget = b.get(id); var record = r.get(id); var picture = m.get(id); var path = p.get(id);
            return new Aggregate(head(plan), budget == null ? null : budget.getTotalAmountKrw(), record == null ? null : record.getActualTotalKrw(),
                    budget == null ? null : budget.getItemCount(), record == null ? null : record.getRecordCount(), record == null ? null : record.getMemoryRecordCount(),
                    picture == null ? null : picture.getMediaItemCount(), path == null ? null : path.getRouteSegmentCount(), path == null ? null : path.getTotalDistanceKm(),
                    path == null ? null : path.getTotalDurationMinutes(), path == null ? null : path.getTotalStepCount());
        }).toList();
    }
    @Override public TravelPlanDetailData.Detail detail(Long owner, Long id) {
        var plan = plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found."));
        var budget = budgets.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(id, owner);
        var ledger = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(id, owner).stream().filter(r -> ledger(r)).sorted(RECORD_ORDER).toList();
        var memories = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(id, owner).stream().filter(r -> memory(r)).sorted(RECORD_ORDER).toList();
        var pictures = media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(id, owner).stream().sorted(MEDIA_ORDER).toList();
        var paths = routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(id, owner).stream().sorted(ROUTE_ORDER).toList();
        return snapshot(plan, budget, ledger, memories, pictures, paths, payload);
    }
    @Override public Rows portfolio(Long owner) {
        var owned = plans.findAllByOwnerIdOrderByStartDateDescIdDesc(owner);
        var budget = budgets.findAllByPlanOwnerId(owner).stream().sorted(BUDGET_ORDER).toList();
        var allRecords = records.findAllByPlanOwnerId(owner).stream().sorted(RECORD_ORDER).toList();
        var ledger = allRecords.stream().filter(this::ledger).toList(); var memories = allRecords.stream().filter(this::memory).toList();
        var pictures = media.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(owner).stream().sorted(MEDIA_ORDER).toList();
        var paths = routes.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(owner).stream().sorted(ROUTE_ORDER).toList();
        return new Rows(owned.stream().map(this::head).toList(), budget.stream().map(b -> new Budget(b.getPlan().getId(), new TravelBudgetData.Result(b.getId(),
                new TravelBudgetData.Value(b.getCategory(), b.getTitle(), b.getAmount(), b.getCurrencyCode(), b.getExchangeRateToKrw(), b.getAmountKrw(), b.getMemo(), b.getDisplayOrder())))).toList(),
                ledger.stream().map(this::record).toList(), memories.stream().map(this::record).toList(), pictures.stream().map(this::picture).toList(), paths.stream().map(this::route).toList());
    }
    private Head head(TravelPlan p) { return new Head(p.getId(), p.getName(), p.getDestination(), p.getStartDate(), p.getEndDate(), p.getHomeCurrency(), p.getHeadCount(), p.getStatus() == null ? null : p.getStatus().name(), p.getColorHex(), p.getMemo()); }
    private boolean ledger(TravelExpenseRecord r) { return TravelRecordPolicy.matches(r.getRecordType(), TravelRecordType.LEDGER); }
    private boolean memory(TravelExpenseRecord r) { return TravelRecordPolicy.matches(r.getRecordType(), TravelRecordType.MEMORY); }
    private TravelRecordData.Result record(TravelExpenseRecord r) {
        var p = r.getPlan(); return new TravelRecordData.Result(r.getId(), new TravelRecordData.Plan(p.getId(), p.getName(), p.getColorHex()),
                new TravelRecordData.Value(r.getRecordType(), r.getExpenseDate(), r.getExpenseTime(), r.getCategory(), r.getTitle(), r.getAmount(), r.getCurrencyCode(), r.getExchangeRateToKrw(), r.getAmountKrw(),
                        r.getCountry(), r.getRegion(), r.getPlaceName(), r.getLatitude(), r.getLongitude(), Boolean.TRUE.equals(r.getSharedWithCommunity()), r.getMemo()));
    }
    private TravelClusterDetailData.Media picture(TravelMediaAsset m) {
        var r = m.getRecord(); var p = m.getPlan();
        return new TravelClusterDetailData.Media(m.getId(), p.getId(), p.getName(), p.getColorHex(), r.getId(), r.getRecordType(), m.getMediaType(), m.getOriginalFileName(), m.getContentType(), m.getFileSize(),
                m.getCaption(), m.getUploadedBy().getDisplayName(), m.getUploadedAt(), r.getExpenseDate(), r.getExpenseTime(), r.getTitle(), r.getAmount(), r.getCurrencyCode(), r.getAmountKrw(),
                r.getCountry(), r.getRegion(), r.getPlaceName(), r.getLatitude(), r.getLongitude(), m.getGpsLatitude(), m.getGpsLongitude(), Boolean.TRUE.equals(m.getRepresentativeOverride()));
    }
    private TravelOwnedMapData.Route route(TravelRouteSegment r) {
        var p = r.getPlan(); return new TravelOwnedMapData.Route(r.getId(), new TravelRouteData.Plan(p.getId(), p.getName(), p.getColorHex()),
                new TravelRouteData.Value(r.getRouteDate(), r.getTitle(), r.getTransportMode(), r.getDistanceKm(), r.getDurationMinutes(), r.getStepCount(), r.getSourceType(), r.getStartPlaceName(), r.getEndPlaceName(),
                        r.getRoutePathJson(), r.getLineColorHex(), r.getLineStyle(), r.getMemo()), r.getGpxFilesJson());
    }
}
