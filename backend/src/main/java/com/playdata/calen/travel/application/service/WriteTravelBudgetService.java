package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.application.port.out.TravelBudgetStore;
import com.playdata.calen.travel.application.port.out.TravelRatePort;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.model.TravelBudgetData.*;
import com.playdata.calen.travel.domain.policy.TravelMoneyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class WriteTravelBudgetService implements WriteTravelBudgetUseCase {
    private final TravelBudgetStore budgets;
    private final TravelRatePort rates;
    private final TravelSummaryInvalidationPort summaries;

    public WriteTravelBudgetService(TravelBudgetStore budgets, TravelRatePort rates, TravelSummaryInvalidationPort summaries) {
        this.budgets = budgets;
        this.rates = rates;
        this.summaries = summaries;
    }

    @Override public Result create(Long owner, Long planId, Draft draft) {
        budgets.requireOwnedPlan(owner, planId);
        Value value = prepare(draft);
        Long id = budgets.create(planId, value);
        summaries.invalidateOwner(owner);
        return new Result(id, value);
    }

    @Override public Result update(Long owner, Long itemId, Draft draft) {
        budgets.requireOwnedBudget(owner, itemId);
        Value value = prepare(draft);
        budgets.update(itemId, value);
        summaries.invalidateOwner(owner);
        return new Result(itemId, value);
    }

    @Override public void delete(Long owner, Long itemId) {
        budgets.requireOwnedBudget(owner, itemId);
        budgets.delete(itemId);
        summaries.invalidateOwner(owner);
    }

    private Value prepare(Draft draft) {
        String currency = TravelMoneyPolicy.currency(draft.currencyCode());
        BigDecimal rate = TravelMoneyPolicy.rate(currency, draft.exchangeRateToKrw());
        if (rate == null) rate = rates.requiredRate(currency).setScale(6, RoundingMode.HALF_UP);
        return TravelMoneyPolicy.budget(draft, currency, rate);
    }
}
