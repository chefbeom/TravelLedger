package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.application.port.out.TravelBudgetStore;
import com.playdata.calen.travel.application.port.out.TravelRatePort;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.application.service.WriteTravelBudgetService;
import com.playdata.calen.travel.domain.model.TravelBudgetData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalTravelBudgets implements WriteTravelBudgetUseCase {
    private final WriteTravelBudgetUseCase delegate;

    public TransactionalTravelBudgets(TravelBudgetStore budgets, TravelRatePort rates, TravelSummaryInvalidationPort summaries) {
        delegate = new WriteTravelBudgetService(budgets, rates, summaries);
    }

    @Override public Result create(Long owner, Long planId, Draft draft) { return delegate.create(owner, planId, draft); }
    @Override public Result update(Long owner, Long itemId, Draft draft) { return delegate.update(owner, itemId, draft); }
    @Override public void delete(Long owner, Long itemId) { delegate.delete(owner, itemId); }
}
