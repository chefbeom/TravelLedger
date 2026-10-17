package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelRecordData.*;

public interface WriteTravelRecordUseCase {
    Result createExpense(Long owner, Long planId, ExpenseDraft draft);
    Result updateExpense(Long owner, Long recordId, ExpenseDraft draft);
    void deleteExpense(Long owner, Long recordId);
    Result createMemory(Long owner, Long planId, MemoryDraft draft);
    Result updateMemory(Long owner, Long recordId, MemoryDraft draft);
    void deleteMemory(Long owner, Long recordId);
}
