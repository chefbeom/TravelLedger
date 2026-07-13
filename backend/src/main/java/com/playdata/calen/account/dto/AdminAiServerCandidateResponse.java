package com.playdata.calen.account.dto;

public record AdminAiServerCandidateResponse(
        String presetKey,
        String title,
        String provider,
        String model,
        String baseUrl,
        String chatPath,
        String modelsPath,
        double temperature,
        int maxTokens,
        boolean apiKeyConfigured
) {
}