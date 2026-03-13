package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CalendarSummaryItemResponse(
        LocalDate date,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        long entryCount
) {
}
