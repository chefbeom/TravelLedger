package com.playdata.calen.account.dto;

public record AdminAiControlResponse(
        boolean enabled,
        String provider,
        String model,
        String workflowUrl,
        String apiKeyHeader,
        boolean apiKeyConfigured,
        String lmStudioBaseUrl,
        String lmStudioChatPath,
        String lmStudioModelsPath,
        boolean lmStudioApiKeyConfigured,
        double temperature,
        int maxTokens,
        long connectTimeoutSeconds,
        long readTimeoutSeconds,
        boolean enforceProviderUrlAllowlist,
        String allowedProviderHosts,
        boolean configured,
        String statusMessage
) {
}