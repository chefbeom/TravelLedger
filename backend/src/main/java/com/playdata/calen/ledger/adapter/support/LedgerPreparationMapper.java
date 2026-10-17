package com.playdata.calen.ledger.adapter.support;

import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Draft;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;

public final class LedgerPreparationMapper {
    private LedgerPreparationMapper() {}
    public static Draft draft(LedgerEntryRequest request) {
        return new Draft(request.entryDate(), request.entryTime(), request.title(), request.memo(), request.amount(),
                request.foreignCurrencyCode(), request.foreignAmount(), request.exchangeRateToKrw(), request.entryType(),
                request.categoryGroupId(), request.categoryDetailId(), request.paymentMethodId(), request.travelPlanId(), request.travelRecordId());
    }
}
