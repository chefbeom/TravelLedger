package com.playdata.calen.ledger.ai;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LedgerAiAnalysisTextSanitizer {

    private static final Pattern SENSITIVE_URL_PATTERN = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SECRET_WORD_PATTERN = Pattern.compile(
            "(?i)\\b[A-Za-z0-9._~/-]*(?:api[-_]?key|secret|token|password)[A-Za-z0-9._~/-]*\\b"
    );
    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile(
            "(?i)authorization\\s*[:=]\\s*(?:bearer|basic)?\\s*\\S+"
    );
    private static final Pattern REDACTED_RUN_PATTERN = Pattern.compile("(?:\\[redacted]\\s*){2,}");

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

    public String redactSensitiveText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String sanitized = SENSITIVE_URL_PATTERN.matcher(value).replaceAll("[redacted]");
        sanitized = AUTHORIZATION_HEADER_PATTERN.matcher(sanitized).replaceAll("[redacted]");
        sanitized = SECRET_WORD_PATTERN.matcher(sanitized).replaceAll("[redacted]");
        sanitized = REDACTED_RUN_PATTERN.matcher(sanitized).replaceAll("[redacted] ");
        return limitText(sanitized, limit);
    }
}