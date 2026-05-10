package com.playdata.calen.ledger.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LedgerEntryChangeHistoryDetailResponse(
        Long id,
        LocalDateTime createdAt,
        String action,
        String actionLabel,
        int entryCount,
        String summary,
        List<LedgerEntryChangeItemResponse> changes
) {
}
