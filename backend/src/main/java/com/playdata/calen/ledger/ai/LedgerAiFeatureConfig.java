package com.playdata.calen.ledger.ai;

public record LedgerAiFeatureConfig(
        LedgerAiProvider provider,
        String model,
        String baseUrl,
        String chatPath,
        String modelsPath,
        String apiKey,
        double temperature,
        int maxTokens
) {
    public boolean usesOllama() {
        return provider == LedgerAiProvider.OLLAMA;
    }

    public String providerLabel() {
        return switch (provider) {
            case OPENAI -> "OpenAI API";
            case OLLAMA -> "Ollama";
            case LMSTUDIO -> "LM Studio";
            case N8N -> "n8n";
        };
    }
}