package com.playdata.calen.ledger.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LedgerEmbeddingPointIdsTest {

    @Test void matchesPythonStandardUuid5ForAllRecordTypesAndOwners() {
        assertThat(LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.LEDGER_ENTRY, 123L).toString())
                .isEqualTo("478b3f6d-a9a4-5cbc-8399-bd378f740e80");
        assertThat(LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.RECURRING_RULE, 123L).toString())
                .isEqualTo("136b73b8-3e73-5317-bcb3-76e8d75cac5b");
        assertThat(LedgerEmbeddingPointIds.forRecord(8L, LedgerEmbeddingRecordType.LEDGER_ENTRY, 123L).toString())
                .isEqualTo("4eb0025f-93f8-50f0-be32-984eba0223bb");
    }

    @Test void isStableHasVersionFiveAndRfcVariantAndChangesWithSourceId() {
        var first = LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.LEDGER_ENTRY, 123L);
        assertThat(first).isEqualTo(LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.LEDGER_ENTRY, 123L));
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(first).isNotEqualTo(LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.LEDGER_ENTRY, 124L));
    }

    @Test void rejectsNonPersistedIdsAndMissingType() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> LedgerEmbeddingPointIds.forRecord(id, LedgerEmbeddingRecordType.LEDGER_ENTRY, 123L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LedgerEmbeddingPointIds.forRecord(7L, LedgerEmbeddingRecordType.LEDGER_ENTRY, id))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> LedgerEmbeddingPointIds.forRecord(7L, null, 123L)).isInstanceOf(NullPointerException.class);
    }
}
