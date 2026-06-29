package com.playdata.calen.ledger.dto;

public record LedgerAiAnalysisStatusResponse(
        boolean enabled,
        boolean configured,
        boolean workflowConfigured,
        boolean apiKeyConfigured,
        String model,
        String message
) {
}
