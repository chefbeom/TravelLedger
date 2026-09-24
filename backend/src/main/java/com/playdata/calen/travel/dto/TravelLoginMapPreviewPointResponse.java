package com.playdata.calen.travel.dto;

import java.math.BigDecimal;

public record TravelLoginMapPreviewPointResponse(
        BigDecimal latitude,
        BigDecimal longitude
) {
}
