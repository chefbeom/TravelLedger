package com.playdata.calen.account.dto;

public record AdminAiControlUpdateRequest(
        Boolean enabled,
        String provider,
        String model,
        String workflowUrl,
        String apiKeyHeader,
        String apiKey,
        Boolean clearApiKey,
        String lmStudioBaseUrl,
        String lmStudioChatPath,
        String lmStudioModelsPath,
        String lmStudioApiKey,
        Boolean clearLmStudioApiKey,
        String openAiBaseUrl,
        String openAiChatPath,
        String openAiModelsPath,
        String openAiApiKey,
        Boolean clearOpenAiApiKey,
        Double temperature,
        Integer maxTokens,
        Long connectTimeoutSeconds,
        Long readTimeoutSeconds,
        Boolean enforceProviderUrlAllowlist,
        String allowedProviderHosts,
        String presetKey,
        Boolean reuseExistingSecrets
) {
}
