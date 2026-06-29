package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerTransactionAnomalyGroupResponse(
        String type,
        String severity,
        String reason,
        String anomalyKey,
        int entryCount,
        List<LedgerTransactionAnomalyEntryResponse> entries
) {
}