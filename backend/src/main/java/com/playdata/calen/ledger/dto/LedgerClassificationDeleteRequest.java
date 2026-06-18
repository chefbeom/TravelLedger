package com.playdata.calen.ledger.dto;

public record LedgerClassificationDeleteRequest(
        Long replacementCategoryGroupId,
        Long replacementCategoryDetailId,
        Long replacementPaymentMethodId
) {
}
