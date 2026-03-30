package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TravelRoutePointRequest(
        @NotNull(message = "Latitude is required.")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required.")
        BigDecimal longitude,

        @Size(max = 24, message = "Route point type must be 24 characters or fewer.")
        String pointType,

        Long linkedMemoryId,

        @Size(max = 160, message = "Route point label must be 160 characters or fewer.")
        String label
) {
    public TravelRoutePointRequest(BigDecimal latitude, BigDecimal longitude) {
        this(latitude, longitude, null, null, null);
    }
}
