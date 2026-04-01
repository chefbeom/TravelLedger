package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelCommunityFeedPageResponse(
        List<TravelCommunityPostResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
