package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotNull;

public record TravelPlanPublicShareRequest(
        @NotNull Boolean publicShared
) {
}
