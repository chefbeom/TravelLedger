package com.playdata.calen.ledger.adapter.support;

import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import com.playdata.calen.ledger.dto.*;

/** Only the ledger write adapter translates legacy ledger transport records. */
public final class LedgerTravelMapper {
    private LedgerTravelMapper() {}
    public static LedgerEntryRequest request(Write v) {
        return new LedgerEntryRequest(v.date(), v.time(), v.title(), v.memo(), v.amount(), v.currency(), v.foreignAmount(), v.rate(),
                v.type(), v.group(), v.detail(), v.payment(), v.plan(), v.record());
    }
    public static LedgerResult value(LedgerEntryResponse v) {
        return new LedgerResult(v.id(), v.entryDate(), v.entryTime(), v.title(), v.memo(), v.amount(), v.foreignCurrencyCode(), v.foreignAmount(), v.exchangeRateToKrw(),
                v.exchangeRateDate(), v.exchangeRateProvider(), v.entryType(), v.categoryGroupId(), v.categoryGroupName(), v.categoryDetailId(), v.categoryDetailName(),
                v.paymentMethodId(), v.paymentMethodName(), v.paymentMethodKind(), v.travelPlanId(), v.travelRecordId());
    }
    public static LedgerEntryResponse response(LedgerResult v) {
        return new LedgerEntryResponse(v.id(), v.date(), v.time(), v.title(), v.memo(), v.amount(), v.currency(), v.foreignAmount(), v.rate(), v.rateDate(), v.rateProvider(),
                v.type(), v.group(), v.groupName(), v.detail(), v.detailName(), v.payment(), v.paymentName(), v.paymentKind(), v.plan(), v.record());
    }
}
