package com.playdata.calen.account.dto;

public record HouseholdAggregateWidgetResponse(
        String kind,
        String period,
        Long paymentMethodId,
        String amountType,
        Long monthlyExpenseTarget,
        Long singleExpenseLimit,
        Boolean showIncomeCumulative,
        Boolean showExpenseCumulative,
        Boolean comparePreviousPeriod,
        Integer layoutX,
        Integer layoutY,
        Integer layoutW,
        Integer layoutH,
        Integer layoutOrder,
        String textSize,
        String textColor
) {
}
