package com.playdata.calen.ledger.ai;

import org.springframework.stereotype.Component;

@Component
public class LedgerAiAnalysisTextSanitizer {

    public String safeText(String value) {
        return value == null ? "" : value;
    }

    public boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String limitText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit);
    }
}