package com.playdata.calen.account.dto;

import java.util.List;

public record HouseholdAggregatePreferencesResponse(
        List<HouseholdAggregateWidgetResponse> widgets
) {
}
