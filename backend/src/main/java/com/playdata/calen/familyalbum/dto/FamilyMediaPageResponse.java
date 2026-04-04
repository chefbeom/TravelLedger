package com.playdata.calen.familyalbum.dto;

import java.util.List;

public record FamilyMediaPageResponse(
        List<FamilyMediaResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
