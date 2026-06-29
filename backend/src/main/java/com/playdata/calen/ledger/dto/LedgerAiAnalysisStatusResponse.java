package com.playdata.calen.ledger.dto;

public record LedgerAiAnalysisStatusResponse(
        boolean enabled,
        boolean configured,
        String provider,
        boolean workflowConfigured,
        boolean apiKeyConfigured,
        boolean lmStudioConfigured,
        String model,
        String message
) {
}