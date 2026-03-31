package com.playdata.calen.account.dto;

public record HouseholdAggregateWidgetRequest(
        String kind,
        String period,
        Long paymentMethodId,
        String amountType
) {
}
