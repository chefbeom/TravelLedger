package com.playdata.calen.ledger.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LedgerTransactionAnomalyResponse(
        LocalDate from,
        LocalDate to,
        int totalGroups,
        int returnedGroups,
        Instant generatedAt,
        List<LedgerTransactionAnomalyGroupResponse> content
) {
}