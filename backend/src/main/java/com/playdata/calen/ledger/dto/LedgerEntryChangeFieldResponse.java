package com.playdata.calen.ledger.dto;

public record LedgerEntryChangeFieldResponse(
        String field,
        String beforeValue,
        String afterValue
) {
}
