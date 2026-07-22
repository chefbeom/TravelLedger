package com.playdata.calen.account.dto;

/**
 * Stores only the provider credential for a reusable AI server profile.
 * Routing a profile to an AI feature is intentionally handled separately.
 */
public record AdminAiServerProfileSecretUpdateRequest(
        String presetKey,
        String provider,
        String lmStudioApiKey,
        Boolean clearLmStudioApiKey,
        String openAiApiKey,
        Boolean clearOpenAiApiKey,
        String ollamaApiKey,
        Boolean clearOllamaApiKey
) {
}
