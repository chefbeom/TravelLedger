package com.playdata.calen.ledger.dto;

public record LedgerImageConcurrencyResponse(int maxConcurrent, int minAllowed, int maxAllowed) {
}
