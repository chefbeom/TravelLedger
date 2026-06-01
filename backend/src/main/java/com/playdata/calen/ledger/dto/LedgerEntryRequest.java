package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record LedgerEntryRequest(
        @NotNull(message = "Entry date is required.")
        LocalDate entryDate,
        LocalTime entryTime,
        @NotBlank(message = "Title is required.")
        String title,
        String memo,
        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0.")
        BigDecimal amount,
        String foreignCurrencyCode,
        BigDecimal foreignAmount,
        BigDecimal exchangeRateToKrw,
        @NotNull(message = "Entry type is required.")
        EntryType entryType,
        @NotNull(message = "Category group is required.")
        Long categoryGroupId,
        Long categoryDetailId,
        Long paymentMethodId,
        Long travelPlanId,
        Long travelRecordId
) {
}
