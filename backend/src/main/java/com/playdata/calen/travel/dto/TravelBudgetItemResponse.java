package com.playdata.calen.travel.dto;

import java.math.BigDecimal;

public record TravelBudgetItemResponse(
        Long id,
        String category,
        String title,
        BigDecimal amount,
        String currencyCode,
        BigDecimal exchangeRateToKrw,
        BigDecimal amountKrw,
        String memo,
        Integer displayOrder
) {
}
