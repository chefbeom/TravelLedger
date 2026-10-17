package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelBudgetData.*;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;

public final class TravelBudgetMapper {
    private TravelBudgetMapper() {}
    public static Draft draft(TravelBudgetItemRequest request) {
        return new Draft(request.category(), request.title(), request.amount(), request.currencyCode(), request.exchangeRateToKrw(), request.memo(), request.displayOrder());
    }
    public static TravelBudgetItemResponse response(Result result) {
        var value = result.value();
        return new TravelBudgetItemResponse(result.id(), value.category(), value.title(), value.amount(), value.currencyCode(),
                value.exchangeRateToKrw(), value.amountKrw(), value.memo(), value.displayOrder());
    }
}
