package com.playdata.calen.account.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record HouseholdPersonalizationResponse(String revision, JsonNode preferences, JsonNode layout,
        List<HouseholdAggregateWidgetResponse> widgets) {}
