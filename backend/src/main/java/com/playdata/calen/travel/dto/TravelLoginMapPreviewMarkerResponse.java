package com.playdata.calen.travel.dto;

import java.math.BigDecimal;

public record TravelLoginMapPreviewMarkerResponse(
        int number,
        BigDecimal latitude,
        BigDecimal longitude,
        String thumbnailUrl
) {
}
