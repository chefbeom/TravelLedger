package com.playdata.calen.ledger.dto;

public record DashboardCardResponse(
        String key,
        String label,
        OverviewResponse overview
) {
}
