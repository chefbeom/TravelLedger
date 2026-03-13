package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelMediaType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TravelMediaUploadPrepareRequest(
        @NotNull TravelMediaType mediaType,
        String caption,
        @NotEmpty List<@Valid TravelMediaUploadFileRequest> files
) {
}
