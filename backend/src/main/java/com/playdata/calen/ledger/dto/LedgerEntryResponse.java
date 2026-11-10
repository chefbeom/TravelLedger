package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

public record LedgerEntryResponse(
        Long id,
        LocalDate entryDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime entryTime,
        String title,
        String memo,
        BigDecimal amount,
        String foreignCurrencyCode,
        BigDecimal foreignAmount,
        BigDecimal exchangeRateToKrw,
        LocalDate exchangeRateDate,
        String exchangeRateProvider,
        EntryType entryType,
        Long categoryGroupId,
        String categoryGroupName,
        Long categoryDetailId,
        String categoryDetailName,
        Long paymentMethodId,
        String paymentMethodName,
        PaymentMethodKind paymentMethodKind,
        Long travelPlanId,
        Long travelRecordId,
        boolean financiallyExcluded,
        LocalDateTime cancelledAt,
        BigDecimal refundedPrincipal,
        boolean hasAdjustmentHistory,
        LedgerAdjustmentType adjustmentType,
        Long adjustmentOriginId,
        BigDecimal adjustmentPrincipal,
        BigDecimal adjustmentDeduction,
        BigDecimal adjustmentExtra,
        LocalDateTime adjustmentVoidedAt
) {
    /** Compatibility for existing internal callers and legacy share snapshots. */
    public LedgerEntryResponse(Long id, LocalDate entryDate, LocalTime entryTime, String title, String memo,
            BigDecimal amount, String foreignCurrencyCode, BigDecimal foreignAmount, BigDecimal exchangeRateToKrw,
            LocalDate exchangeRateDate, String exchangeRateProvider, EntryType entryType,
            Long categoryGroupId, String categoryGroupName, Long categoryDetailId, String categoryDetailName,
            Long paymentMethodId, String paymentMethodName, PaymentMethodKind paymentMethodKind,
            Long travelPlanId, Long travelRecordId) {
        this(id, entryDate, entryTime, title, memo, amount, foreignCurrencyCode, foreignAmount, exchangeRateToKrw,
                exchangeRateDate, exchangeRateProvider, entryType, categoryGroupId, categoryGroupName,
                categoryDetailId, categoryDetailName, paymentMethodId, paymentMethodName, paymentMethodKind,
                travelPlanId, travelRecordId, false, null, BigDecimal.ZERO, false, null, null, null, null, null, null);
    }
}
