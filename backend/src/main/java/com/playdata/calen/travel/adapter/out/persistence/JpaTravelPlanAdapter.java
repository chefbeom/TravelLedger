package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelPlanStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelPlanAdapter implements TravelPlanStore {
    private final AppUserService users;
    private final AppUserRepository userRows;
    private final TravelPlanRepository plans;
    private final TravelBudgetItemRepository budgets;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    private final TravelPlanShareRepository shares;

    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public void requireOwnedPlan(Long owner, Long id) {
        plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found."));
    }

    @Override public Long create(Long owner, Value value) {
        var plan = new TravelPlan();
        plan.setOwner(userRows.getReferenceById(owner));
        apply(plan, value);
        return plans.save(plan).getId();
    }

    @Override public void update(Long id, Value value) { apply(plans.getReferenceById(id), value); }

    @Override public Metrics metrics(Long owner, Long planId) {
        var budget = budgets.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(planId, owner);
        var ledger = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(planId, owner).stream()
                .filter(r -> r.getRecordType() == null || r.getRecordType() == TravelRecordType.LEDGER).toList();
        var memories = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(planId, owner).stream()
                .filter(r -> r.getRecordType() == TravelRecordType.MEMORY).toList();
        var pictures = media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(planId, owner);
        var paths = routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(planId, owner);
        return new Metrics(sum(budget, TravelBudgetItem::getAmountKrw, 2), sum(ledger, TravelExpenseRecord::getAmountKrw, 2),
                budget.size(), ledger.size(), memories.size(), pictures.size(), paths.size(), sum(paths, TravelRouteSegment::getDistanceKm, 3),
                sumInt(paths, TravelRouteSegment::getDurationMinutes), sumInt(paths, TravelRouteSegment::getStepCount));
    }

    @Override public void deletePlanAndRemainingRelations(Long id) {
        shares.deleteAllByPlanId(id);
        budgets.deleteAllByPlanId(id);
        records.deleteAllByPlanId(id);
        plans.delete(plans.getReferenceById(id));
    }

    private void apply(TravelPlan plan, Value value) {
        plan.setName(value.name());
        plan.setDestination(value.destination());
        plan.setStartDate(value.startDate());
        plan.setEndDate(value.endDate());
        plan.setHomeCurrency(value.homeCurrency());
        plan.setHeadCount(value.headCount());
        plan.setStatus(value.status());
        plan.setColorHex(value.colorHex());
        plan.setMemo(value.memo());
    }

    private <T> BigDecimal sum(Collection<T> items, Function<T, BigDecimal> value, int scale) {
        return items.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add).setScale(scale, RoundingMode.HALF_UP);
    }

    private <T> Integer sumInt(Collection<T> items, Function<T, Integer> value) {
        return items.stream().map(value).filter(Objects::nonNull).reduce(0, Integer::sum);
    }
}
