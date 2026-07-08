package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record TravelMapShareLinkRequest(
        String title,
        @NotEmpty List<Long> planIds,
        List<Long> excludedRecordIds,
        List<Long> excludedMediaIds,
        List<Long> excludedRouteIds
) {
}
