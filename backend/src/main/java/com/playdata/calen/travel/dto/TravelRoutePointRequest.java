package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TravelRoutePointRequest(
        @NotNull(message = "Latitude is required.")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required.")
        BigDecimal longitude
) {
}
