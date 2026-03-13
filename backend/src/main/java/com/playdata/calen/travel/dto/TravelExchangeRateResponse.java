package com.playdata.calen.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TravelExchangeRateResponse(
        String currencyCode,
        BigDecimal rateToKrw,
        LocalDate fetchedDate,
        boolean available,
        String provider
) {
}
