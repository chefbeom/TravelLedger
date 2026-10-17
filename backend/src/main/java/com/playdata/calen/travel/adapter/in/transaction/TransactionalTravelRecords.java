package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.WriteTravelRecordService;
import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalTravelRecords implements WriteTravelRecordUseCase {
    private final WriteTravelRecordUseCase delegate;
    public TransactionalTravelRecords(TravelRecordStore records, TravelRatePort rates,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        delegate = new WriteTravelRecordService(records, rates, summaries, clusters);
    }
    @Override public Result createExpense(Long owner, Long plan, ExpenseDraft draft) { return delegate.createExpense(owner, plan, draft); }
    @Override public Result updateExpense(Long owner, Long id, ExpenseDraft draft) { return delegate.updateExpense(owner, id, draft); }
    @Override public void deleteExpense(Long owner, Long id) { delegate.deleteExpense(owner, id); }
    @Override public Result createMemory(Long owner, Long plan, MemoryDraft draft) { return delegate.createMemory(owner, plan, draft); }
    @Override public Result updateMemory(Long owner, Long id, MemoryDraft draft) { return delegate.updateMemory(owner, id, draft); }
    @Override public void deleteMemory(Long owner, Long id) { delegate.deleteMemory(owner, id); }
}
