package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;

public record TravelPlanShareRequest(
        @NotBlank String loginId
) {
}
