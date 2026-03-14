package com.playdata.calen.ledger.service;

import java.util.Optional;

final class LedgerEntryTextSanitizer {

    static final int TITLE_MAX_LENGTH = 120;
    static final int MEMO_MAX_LENGTH = 500;

    private LedgerEntryTextSanitizer() {
    }

    static SanitizedLedgerText sanitize(String title, String memo) {
        String normalizedTitle = Optional.ofNullable(title).map(String::trim).orElse("");
        String normalizedMemo = blankToNull(memo);

        if (normalizedTitle.length() <= TITLE_MAX_LENGTH) {
            return new SanitizedLedgerText(normalizedTitle, truncate(normalizedMemo, MEMO_MAX_LENGTH));
        }

        String shortenedTitle = normalizedTitle.substring(0, TITLE_MAX_LENGTH).trim();
        String overflowMemo = appendMemoLine(normalizedMemo, "Full title: " + normalizedTitle);
        return new SanitizedLedgerText(shortenedTitle, truncate(overflowMemo, MEMO_MAX_LENGTH));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String appendMemoLine(String memo, String line) {
        if (memo == null || memo.isBlank()) {
            return line;
        }
        return memo + System.lineSeparator() + line;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    record SanitizedLedgerText(String title, String memo) {
    }
}
