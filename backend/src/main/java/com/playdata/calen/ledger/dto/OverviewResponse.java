package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OverviewResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        long entryCount
) {
}
