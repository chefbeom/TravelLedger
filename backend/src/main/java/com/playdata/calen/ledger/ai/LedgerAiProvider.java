package com.playdata.calen.ledger.ai;

import java.util.Locale;

public enum LedgerAiProvider {
    N8N,
    LMSTUDIO;

    public static LedgerAiProvider from(String value) {
        if (value == null || value.isBlank()) {
            return N8N;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lmstudio", "lm-studio", "lm_studio", "openai", "openai-compatible" -> LMSTUDIO;
            default -> N8N;
        };
    }
}