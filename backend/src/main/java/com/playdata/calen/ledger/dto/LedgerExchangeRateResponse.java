package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LedgerExchangeRateResponse(
        String currencyCode,
        BigDecimal rateToKrw,
        LocalDate rateDate,
        boolean available,
        String provider
) {
}
