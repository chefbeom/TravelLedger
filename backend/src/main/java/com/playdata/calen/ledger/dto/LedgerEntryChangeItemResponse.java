package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerEntryChangeItemResponse(
        Long entryId,
        String beforeTitle,
        String afterTitle,
        List<LedgerEntryChangeFieldResponse> fields
) {
}
