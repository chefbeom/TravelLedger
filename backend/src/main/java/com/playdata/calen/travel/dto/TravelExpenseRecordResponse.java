package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelExpenseRecordResponse(
        Long id,
        Long planId,
        String planName,
        String planColorHex,
        TravelRecordType recordType,
        LocalDate expenseDate,
        LocalTime expenseTime,
        String category,
        String title,
        BigDecimal amount,
        String currencyCode,
        BigDecimal exchangeRateToKrw,
        BigDecimal amountKrw,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        String memo
) {
}
