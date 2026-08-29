package com.playdata.calen.travel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record TravelRouteGpxUploadCompleteRequest(
        @NotEmpty List<@Valid TravelRouteGpxUploadCompleteFileRequest> files
) {
}
