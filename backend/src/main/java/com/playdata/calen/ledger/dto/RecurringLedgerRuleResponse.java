package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RecurringLedgerRuleResponse(
        Long id,
        String title,
        String memo,
        BigDecimal amount,
        EntryType entryType,
        RecurringLedgerScheduleType scheduleType,
        Integer monthInterval,
        Integer dayOfMonth,
        Integer intervalDays,
        LocalDate startDate,
        LocalDate endDate,
        RecurringLedgerMode mode,
        boolean active,
        Long categoryGroupId,
        String categoryGroupName,
        Long categoryDetailId,
        String categoryDetailName,
        Long paymentMethodId,
        String paymentMethodName,
        LocalDate nextDueDate,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
}
