package com.playdata.calen.ledger.ai;

import java.util.Locale;

public enum LedgerAiProvider {
    N8N,
    LMSTUDIO,
    OPENAI,
    OLLAMA;

    public static LedgerAiProvider from(String value) {
        if (value == null || value.isBlank()) {
            return N8N;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lmstudio", "lm-studio", "lm_studio", "openai-compatible" -> LMSTUDIO;
            case "openai", "openai-api", "chatgpt" -> OPENAI;
            case "ollama" -> OLLAMA;
            default -> N8N;
        };
    }
}
