package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.ledger.domain.model.LedgerTravelData.TravelResult;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;

public final class TravelLedgerReflectionMapper {
    private TravelLedgerReflectionMapper() {}
    public static TravelExpenseRecordResponse response(TravelResult v) {
        return new TravelExpenseRecordResponse(v.id(), v.plan(), v.planName(), v.color(), v.kind() == null ? null : TravelRecordType.valueOf(v.kind()), v.date(), v.time(),
                v.category(), v.title(), v.amount(), v.currency(), v.rate(), v.amountKrw(), v.country(), v.region(), v.place(), v.latitude(), v.longitude(), v.memo());
    }
}
