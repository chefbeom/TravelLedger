package com.playdata.calen.travel.dto;

import java.math.BigDecimal;

public record TravelRoutePointResponse(
        BigDecimal latitude,
        BigDecimal longitude
) {
}
