package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.Size;

public record AdminLoginMapPreviewUpdateRequest(
        boolean enabled,
        @Size(max = 2048)
        String shareLinkOrToken,
        boolean clearShareLink
) {
}
