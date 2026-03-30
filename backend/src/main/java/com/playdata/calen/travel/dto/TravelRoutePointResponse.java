package com.playdata.calen.travel.dto;

import java.math.BigDecimal;

public record TravelRoutePointResponse(
        BigDecimal latitude,
        BigDecimal longitude,
        String pointType,
        Long linkedMemoryId,
        String label
) {
    public TravelRoutePointResponse(BigDecimal latitude, BigDecimal longitude) {
        this(latitude, longitude, null, null, null);
    }
}
