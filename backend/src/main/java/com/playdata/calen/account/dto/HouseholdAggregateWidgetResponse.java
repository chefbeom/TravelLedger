package com.playdata.calen.account.dto;

public record HouseholdAggregateWidgetResponse(
        String kind,
        String period,
        Long paymentMethodId
) {
}
