package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRouteLineStyle;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.TravelRouteTransportMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TravelRouteSegmentRequest(
        @NotNull(message = "Route date is required.")
        LocalDate routeDate,

        @NotBlank(message = "Route title is required.")
        @Size(max = 120, message = "Route title must be 120 characters or fewer.")
        String title,

        @NotNull(message = "Transport mode is required.")
        TravelRouteTransportMode transportMode,

        @NotNull(message = "Distance is required.")
        @DecimalMin(value = "0.01", message = "Distance must be greater than 0.")
        BigDecimal distanceKm,

        @NotNull(message = "Duration is required.")
        @PositiveOrZero(message = "Duration must be 0 or greater.")
        Integer durationMinutes,

        @PositiveOrZero(message = "Step count must be 0 or greater.")
        Integer stepCount,

        @NotNull(message = "Route source type is required.")
        TravelRouteSourceType sourceType,

        @Size(max = 160, message = "Start place must be 160 characters or fewer.")
        String startPlaceName,

        @Size(max = 160, message = "End place must be 160 characters or fewer.")
        String endPlaceName,

        @Size(max = 7, message = "Route color must use #RRGGBB format.")
        String lineColorHex,

        TravelRouteLineStyle lineStyle,

        @Size(max = 500, message = "Memo must be 500 characters or fewer.")
        String memo,

        @NotEmpty(message = "At least one route point is required.")
        List<@Valid TravelRoutePointRequest> points
) {
}
