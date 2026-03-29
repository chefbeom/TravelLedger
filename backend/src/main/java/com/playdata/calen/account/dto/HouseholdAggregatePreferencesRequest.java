package com.playdata.calen.account.dto;

import java.util.List;

public record HouseholdAggregatePreferencesRequest(
        List<HouseholdAggregateWidgetRequest> widgets
) {
}
