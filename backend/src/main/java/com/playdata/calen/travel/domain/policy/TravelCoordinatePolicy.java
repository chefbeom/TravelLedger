package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TravelCoordinatePolicy {
    private TravelCoordinatePolicy() {}

    public static void requirePair(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null))
            throw new BadRequestException("Latitude and longitude must be provided together.");
    }

    public static BigDecimal normalize(BigDecimal value, boolean latitude) {
        if (value == null) return null;
        BigDecimal normalized = value.setScale(7, RoundingMode.HALF_UP);
        BigDecimal min = BigDecimal.valueOf(latitude ? -90 : -180), max = BigDecimal.valueOf(latitude ? 90 : 180);
        if (normalized.compareTo(min) < 0 || normalized.compareTo(max) > 0)
            throw new BadRequestException(latitude ? "Latitude must be between -90 and 90." : "Longitude must be between -180 and 180.");
        return normalized;
    }
}
