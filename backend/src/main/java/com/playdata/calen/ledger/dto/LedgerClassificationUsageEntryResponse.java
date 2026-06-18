package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.playdata.calen.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record LedgerClassificationUsageEntryResponse(
        Long id,
        LocalDate entryDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime entryTime,
        String title,
        BigDecimal amount,
        EntryType entryType,
        String categoryGroupName,
        String categoryDetailName,
        String paymentMethodName,
        boolean deleted
) {
}
