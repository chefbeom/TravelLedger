package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;

public record LedgerEntrySearchSummaryResponse(
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        long count
) {
}
