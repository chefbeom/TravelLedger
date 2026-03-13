package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.TravelRouteTransportMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TravelRouteSegmentResponse(
        Long id,
        Long planId,
        String planName,
        String planColorHex,
        LocalDate routeDate,
        String title,
        TravelRouteTransportMode transportMode,
        BigDecimal distanceKm,
        Integer durationMinutes,
        Integer stepCount,
        TravelRouteSourceType sourceType,
        String startPlaceName,
        String endPlaceName,
        String memo,
        List<TravelRoutePointResponse> points
) {
}
