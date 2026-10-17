package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.application.port.out.TravelReferenceStore;
import com.playdata.calen.travel.domain.TravelBudgetItem;
import com.playdata.calen.travel.domain.model.TravelReferenceData;
import com.playdata.calen.travel.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelReferenceAdapter implements TravelReferenceStore {
    private final AppUserService users;
    private final TravelBudgetItemRepository budgets;
    private final TravelExpenseRecordRepository records;
    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public TravelReferenceData.Source catalogSource(Long owner) {
        var budget = budgets.findAllByPlanOwnerId(owner);
        var all = records.findAllByPlanOwnerId(owner);
        return new TravelReferenceData.Source(budget.stream().map(TravelBudgetItem::getCategory).toList(), all.stream().map(r -> new TravelReferenceData.Record(
                r.getRecordType(), r.getCategory(), r.getCountry(), r.getRegion(), r.getPlaceName())).toList());
    }
}
