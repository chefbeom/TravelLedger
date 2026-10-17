package com.playdata.calen.travel.domain.model;

import java.math.BigDecimal;

public final class TravelBudgetData {
    private TravelBudgetData() {}

    public record Draft(String category, String title, BigDecimal amount, String currencyCode,
            BigDecimal exchangeRateToKrw, String memo, Integer displayOrder) {}
    public record Value(String category, String title, BigDecimal amount, String currencyCode,
            BigDecimal exchangeRateToKrw, BigDecimal amountKrw, String memo, Integer displayOrder) {}
    public record Result(Long id, Value value) {}
}
