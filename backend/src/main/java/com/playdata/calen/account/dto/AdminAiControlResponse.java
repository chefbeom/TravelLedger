package com.playdata.calen.account.dto;

import java.util.List;
import java.util.Map;

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
        String openAiBaseUrl,
        String openAiChatPath,
        String openAiModelsPath,
        boolean openAiApiKeyConfigured,
        double temperature,
        int maxTokens,
        long connectTimeoutSeconds,
        long readTimeoutSeconds,
        boolean enforceProviderUrlAllowlist,
        String allowedProviderHosts,
        boolean configured,
        String statusMessage,
        AdminAiFeatureConfigResponse ledgerAnalysis,
        AdminAiFeatureConfigResponse imageAnalysis,
        AdminAiFeatureConfigResponse excelImport,
        List<AdminAiServerCandidateResponse> candidateServers,
        Map<String, String> featureConnections,
        boolean routingConfigured
) {
}