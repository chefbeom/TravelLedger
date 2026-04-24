package com.playdata.calen.account.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record UserLayoutSettingResponse(
        String scope,
        Integer version,
        JsonNode payload,
        LocalDateTime updatedAt
) {
}
