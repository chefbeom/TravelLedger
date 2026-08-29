package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RecurringLedgerOccurrenceResponse(
        Long id,
        Long ruleId,
        String ruleTitle,
        LocalDate scheduledDate,
        EntryType entryType,
        BigDecimal amount,
        RecurringLedgerMode mode,
        RecurringLedgerOccurrenceStatus status,
        Long createdEntryId,
        LocalDateTime processedAt
) {
}
