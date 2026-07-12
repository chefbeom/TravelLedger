package com.playdata.calen.account.dto;

public record AdminAiFeatureConfigResponse(
        String feature,
        String provider,
        String model,
        String baseUrl,
        String chatPath,
        String modelsPath,
        boolean apiKeyConfigured,
        double temperature,
        int maxTokens,
        boolean configured,
        String statusMessage
) {
}