package com.playdata.calen.account.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record UserLayoutSettingRequest(
        Integer version,
        JsonNode payload
) {
}
