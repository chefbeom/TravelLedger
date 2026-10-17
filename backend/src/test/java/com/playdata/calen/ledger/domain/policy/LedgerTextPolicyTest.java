package com.playdata.calen.ledger.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerTextPolicyTest {
    @Test void whitespaceAndEmptyMemoKeepTheExistingContract() {
        assertThat(LedgerTextPolicy.sanitize("  제목  ", " \t "))
                .isEqualTo(new LedgerTextPolicy.SanitizedLedgerText("제목", null));
        assertThat(LedgerTextPolicy.sanitize(null, null))
                .isEqualTo(new LedgerTextPolicy.SanitizedLedgerText("", null));
    }

    @Test void overflowTitleMovesToMemoWithoutChangingLimits() {
        String original = "title".repeat(30);
        var result = LedgerTextPolicy.sanitize(original, " note ");
        assertThat(result.title()).isEqualTo(original.substring(0, 120));
        assertThat(result.memo()).isEqualTo("note" + System.lineSeparator() + "Full title: " + original);
    }

    @Test void memoIsTruncatedAfterAppendingTheFullTitle() {
        var result = LedgerTextPolicy.sanitize("x".repeat(121), "m".repeat(495));
        assertThat(result.title()).hasSize(120);
        assertThat(result.memo()).hasSize(500).startsWith("m".repeat(495));
        assertThat(LedgerTextPolicy.sanitize("short", "m".repeat(501)).memo()).hasSize(500);
    }

    @Test void importedMetadataLinesAreRemovedWithoutDroppingUserMemo() {
        assertThat(LedgerTextPolicy.stripImportedMemo(" Imported from Excel: source.xlsx\r\n\n first \n second "))
                .isEqualTo("first" + System.lineSeparator() + "second");
        assertThat(LedgerTextPolicy.stripImportedMemo("Imported from Excel: source.xlsx")).isNull();
        assertThat(LedgerTextPolicy.stripImportedMemo(null)).isNull();
    }
}
