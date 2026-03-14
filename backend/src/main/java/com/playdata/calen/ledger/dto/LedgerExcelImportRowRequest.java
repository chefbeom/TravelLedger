package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record LedgerExcelImportRowRequest(
        boolean selected,
        String sourceSheetName,
        @NotNull(message = "Entry date is required.")
        LocalDate entryDate,
        LocalTime entryTime,
        @NotBlank(message = "Title is required.")
        String title,
        String memo,
        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero.")
        BigDecimal amount,
        @NotNull(message = "Entry type is required.")
        EntryType entryType,
        String paymentMethodName,
        String categoryGroupName,
        String categoryDetailName,
        Integer sourceRowNumber
) {
}
