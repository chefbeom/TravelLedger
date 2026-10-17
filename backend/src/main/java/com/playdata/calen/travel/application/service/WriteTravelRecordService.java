package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import com.playdata.calen.travel.domain.policy.TravelMoneyPolicy;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class WriteTravelRecordService implements WriteTravelRecordUseCase {
    private final TravelRecordStore records;
    private final TravelRatePort rates;
    private final TravelSummaryInvalidationPort summaries;
    private final TravelClusterRefreshPort clusters;

    public WriteTravelRecordService(TravelRecordStore records, TravelRatePort rates,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        this.records = records;
        this.rates = rates;
        this.summaries = summaries;
        this.clusters = clusters;
    }

    @Override public Result createExpense(Long owner, Long planId, ExpenseDraft draft) {
        Plan plan = records.ownedPlan(owner, planId);
        Value value = expense(draft);
        return saved(owner, records.create(planId, value), plan, value);
    }

    @Override public Result updateExpense(Long owner, Long id, ExpenseDraft draft) {
        Access access = required(owner, id, TravelRecordType.LEDGER);
        Value value = expense(draft);
        records.update(id, value);
        return saved(owner, id, access.plan(), value);
    }

    @Override public Result createMemory(Long owner, Long planId, MemoryDraft draft) {
        Plan plan = records.ownedPlan(owner, planId);
        Value value = TravelRecordPolicy.memory(draft);
        return saved(owner, records.create(planId, value), plan, value);
    }

    @Override public Result updateMemory(Long owner, Long id, MemoryDraft draft) {
        Access access = required(owner, id, TravelRecordType.MEMORY);
        Value value = TravelRecordPolicy.memory(draft);
        records.update(id, value);
        return saved(owner, id, access.plan(), value);
    }

    @Override public void deleteExpense(Long owner, Long id) { delete(owner, id, TravelRecordType.LEDGER); }
    @Override public void deleteMemory(Long owner, Long id) { delete(owner, id, TravelRecordType.MEMORY); }

    private void delete(Long owner, Long id, TravelRecordType type) {
        required(owner, id, type);
        records.removeMedia(owner, id);
        records.delete(id);
        changed(owner, type);
    }

    private Access required(Long owner, Long id, TravelRecordType expected) {
        Access access = records.ownedRecord(owner, id).orElseThrow(() -> TravelRecordPolicy.unavailable(expected));
        if (!TravelRecordPolicy.matches(access.type(), expected)) throw TravelRecordPolicy.unavailable(expected);
        return access;
    }

    private Value expense(ExpenseDraft draft) {
        String currency = TravelMoneyPolicy.currency(draft.currency());
        BigDecimal rate = TravelMoneyPolicy.rate(currency, draft.rate());
        if (rate == null) rate = rates.requiredRate(currency).setScale(6, RoundingMode.HALF_UP);
        return TravelRecordPolicy.expense(draft, currency, rate);
    }

    private Result saved(Long owner, Long id, Plan plan, Value value) {
        var result = new Result(id, new Plan(plan.id(), plan.name(), TravelPlanPolicy.color(plan.colorHex())), value);
        changed(owner, value.type());
        return result;
    }

    private void changed(Long owner, TravelRecordType type) {
        summaries.invalidateOwner(owner);
        // Preserve the distinction: expense CRUD invalidates summaries, memory CRUD also refreshes photo clusters.
        if (type == TravelRecordType.MEMORY) clusters.refreshAfterChange(owner);
    }
}
