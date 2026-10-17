package com.playdata.calen.travel.application.port.out;

import java.math.BigDecimal;

public interface TravelRatePort {
    BigDecimal requiredRate(String currency);
}
