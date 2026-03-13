package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record LedgerEntryResponse(
        Long id,
        LocalDate entryDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime entryTime,
        String title,
        String memo,
        BigDecimal amount,
        EntryType entryType,
        Long categoryGroupId,
        String categoryGroupName,
        Long categoryDetailId,
        String categoryDetailName,
        Long paymentMethodId,
        String paymentMethodName,
        PaymentMethodKind paymentMethodKind
) {
}
