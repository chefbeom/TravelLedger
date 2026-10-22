package com.playdata.calen.account.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record HouseholdPersonalizationRequest(String revision, JsonNode preferences, JsonNode layout,
        List<HouseholdAggregateWidgetRequest> widgets) {}
