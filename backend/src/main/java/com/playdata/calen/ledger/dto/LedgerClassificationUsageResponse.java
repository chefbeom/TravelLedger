package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerClassificationUsageResponse(
        String targetType,
        Long targetId,
        String targetName,
        long totalCount,
        boolean hasMore,
        List<LedgerClassificationUsageEntryResponse> entries
) {
}
