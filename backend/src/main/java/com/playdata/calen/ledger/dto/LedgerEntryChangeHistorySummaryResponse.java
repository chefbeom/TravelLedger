package com.playdata.calen.ledger.dto;

import java.time.LocalDateTime;

public record LedgerEntryChangeHistorySummaryResponse(
        Long id,
        LocalDateTime createdAt,
        String action,
        String actionLabel,
        int entryCount,
        String summary
) {
}
