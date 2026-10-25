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
        Long chartMaxAmount,
        Integer layoutX,
        Integer layoutY,
        Integer layoutW,
        Integer layoutH,
        Integer layoutOrder,
        String textSize,
        String textColor,
        String title,
        Boolean hidden,
        String displaySize,
        Integer cardWidth,
        Integer cardHeight,
        Boolean showPeriod,
        Boolean showDetails,
        Integer cardColumns,
        Integer cardRows
) {
    public HouseholdAggregateWidgetResponse(String kind, String period, Long paymentMethodId, String amountType,
            Long monthlyExpenseTarget, Long singleExpenseLimit, Boolean showIncomeCumulative, Boolean showExpenseCumulative,
            Boolean comparePreviousPeriod, Long chartMaxAmount, Integer layoutX, Integer layoutY, Integer layoutW,
            Integer layoutH, Integer layoutOrder, String textSize, String textColor, String title, Boolean hidden,
            String displaySize, Integer cardWidth, Integer cardHeight, Boolean showPeriod, Boolean showDetails) {
        this(kind, period, paymentMethodId, amountType, monthlyExpenseTarget, singleExpenseLimit, showIncomeCumulative,
                showExpenseCumulative, comparePreviousPeriod, chartMaxAmount, layoutX, layoutY, layoutW, layoutH,
                layoutOrder, textSize, textColor, title, hidden, displaySize, cardWidth, cardHeight, showPeriod, showDetails, null, null);
    }
    public HouseholdAggregateWidgetResponse(String kind, String period, Long paymentMethodId, String amountType,
            Long monthlyExpenseTarget, Long singleExpenseLimit, Boolean showIncomeCumulative, Boolean showExpenseCumulative,
            Boolean comparePreviousPeriod, Long chartMaxAmount, Integer layoutX, Integer layoutY, Integer layoutW,
            Integer layoutH, Integer layoutOrder, String textSize, String textColor, String title, Boolean hidden, String displaySize) {
        this(kind, period, paymentMethodId, amountType, monthlyExpenseTarget, singleExpenseLimit, showIncomeCumulative,
                showExpenseCumulative, comparePreviousPeriod, chartMaxAmount, layoutX, layoutY, layoutW, layoutH,
                layoutOrder, textSize, textColor, title, hidden, displaySize, null, null, true, true);
    }
    public HouseholdAggregateWidgetResponse(String kind, String period, Long paymentMethodId, String amountType,
            Long monthlyExpenseTarget, Long singleExpenseLimit, Boolean showIncomeCumulative, Boolean showExpenseCumulative,
            Boolean comparePreviousPeriod, Long chartMaxAmount, Integer layoutX, Integer layoutY, Integer layoutW,
            Integer layoutH, Integer layoutOrder, String textSize, String textColor) {
        this(kind, period, paymentMethodId, amountType, monthlyExpenseTarget, singleExpenseLimit, showIncomeCumulative,
                showExpenseCumulative, comparePreviousPeriod, chartMaxAmount, layoutX, layoutY, layoutW, layoutH,
                layoutOrder, textSize, textColor, "", false, "MEDIUM");
    }
}
