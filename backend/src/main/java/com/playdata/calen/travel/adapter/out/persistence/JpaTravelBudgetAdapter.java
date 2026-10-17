package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelBudgetStore;
import com.playdata.calen.travel.domain.TravelBudgetItem;
import com.playdata.calen.travel.domain.model.TravelBudgetData.Value;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelBudgetAdapter implements TravelBudgetStore {
    private final TravelPlanRepository plans;
    private final TravelBudgetItemRepository budgets;

    @Override public void requireOwnedPlan(Long owner, Long id) {
        plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found."));
    }

    @Override public void requireOwnedBudget(Long owner, Long id) {
        budgets.findByIdAndPlanOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel budget item not found."));
    }

    @Override public Long create(Long planId, Value value) {
        var item = new TravelBudgetItem();
        item.setPlan(plans.getReferenceById(planId));
        apply(item, value);
        return budgets.save(item).getId();
    }

    @Override public void update(Long itemId, Value value) { apply(budgets.getReferenceById(itemId), value); }

    @Override public void delete(Long itemId) { budgets.delete(budgets.getReferenceById(itemId)); }

    private void apply(TravelBudgetItem item, Value value) {
        item.setCategory(value.category());
        item.setTitle(value.title());
        item.setAmount(value.amount());
        item.setCurrencyCode(value.currencyCode());
        item.setExchangeRateToKrw(value.exchangeRateToKrw());
        item.setAmountKrw(value.amountKrw());
        item.setMemo(value.memo());
        item.setDisplayOrder(value.displayOrder());
    }
}
