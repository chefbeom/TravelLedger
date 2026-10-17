package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import com.playdata.calen.travel.dto.*;

public final class TravelRecordMapper {
    private TravelRecordMapper() {}
    public static ExpenseDraft draft(TravelExpenseRecordRequest request) {
        return new ExpenseDraft(request.expenseDate(), request.expenseTime(), request.category(), request.title(), request.amount(), request.currencyCode(),
                request.exchangeRateToKrw(), request.country(), request.region(), request.placeName(), request.latitude(), request.longitude(), request.memo());
    }
    public static MemoryDraft draft(TravelMemoryRecordRequest request) {
        return new MemoryDraft(request.memoryDate(), request.memoryTime(), request.category(), request.title(), request.country(), request.region(),
                request.placeName(), request.latitude(), request.longitude(), request.sharedWithCommunity(), request.memo());
    }
    public static TravelExpenseRecordResponse expense(Result result) {
        var p = result.plan(); var r = result.value();
        return new TravelExpenseRecordResponse(result.id(), p.id(), p.name(), p.colorHex(), r.type(), r.date(), r.time(), r.category(), r.title(),
                r.amount(), r.currency(), r.rate(), r.amountKrw(), r.country(), r.region(), r.placeName(), r.latitude(), r.longitude(), r.memo());
    }
    public static TravelMemoryRecordResponse memory(Result result) {
        var p = result.plan(); var r = result.value();
        return new TravelMemoryRecordResponse(result.id(), p.id(), p.name(), p.colorHex(), r.type(), r.date(), r.time(), r.category(), r.title(),
                r.country(), r.region(), r.placeName(), r.latitude(), r.longitude(), r.shared(), r.memo());
    }
}
