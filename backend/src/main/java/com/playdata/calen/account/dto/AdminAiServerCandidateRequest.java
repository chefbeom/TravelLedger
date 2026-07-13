package com.playdata.calen.account.dto;

public record AdminAiServerCandidateRequest(
        String presetKey,
        String title,
        String provider,
        String model,
        String baseUrl,
        String chatPath,
        String modelsPath,
        Double temperature,
        Integer maxTokens
) {
}