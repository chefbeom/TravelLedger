package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeriodComparisonItemResponse(
        String label,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance
) {
}
