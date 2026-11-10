package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record LedgerAdjustmentRequest(
        @NotNull LedgerAdjustmentType type,
        @NotNull LocalDate date,
        LocalTime time,
        BigDecimal principal,
        BigDecimal deduction,
        BigDecimal extra,
        Long incomeCategoryGroupId,
        Long receivingPaymentMethodId,
        @Size(max = 500) String memo,
        @NotNull UUID requestKey
) {}
