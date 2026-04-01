package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelSharedExhibitPageResponse(
        List<TravelSharedExhibitSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
