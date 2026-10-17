package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.domain.policy.LedgerTextPolicy;

/** Compatibility boundary for import/export callers not yet moved to the application layer. */
final class LedgerEntryTextSanitizer {
    static final int TITLE_MAX_LENGTH = LedgerTextPolicy.TITLE_MAX_LENGTH;
    static final int MEMO_MAX_LENGTH = LedgerTextPolicy.MEMO_MAX_LENGTH;

    private LedgerEntryTextSanitizer() {}

    static SanitizedLedgerText sanitize(String title, String memo) {
        var text = LedgerTextPolicy.sanitize(title, memo);
        return new SanitizedLedgerText(text.title(), text.memo());
    }

    static String stripImportedMemo(String memo) {
        return LedgerTextPolicy.stripImportedMemo(memo);
    }

    record SanitizedLedgerText(String title, String memo) {}
}
