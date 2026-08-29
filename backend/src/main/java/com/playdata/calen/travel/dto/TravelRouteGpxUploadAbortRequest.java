package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TravelRouteGpxUploadAbortRequest(
        @NotEmpty List<@NotBlank String> objectKeys
) {
}
