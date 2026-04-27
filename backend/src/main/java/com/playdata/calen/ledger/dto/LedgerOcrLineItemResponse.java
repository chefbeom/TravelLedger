package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;

public record LedgerOcrLineItemResponse(
        String itemName,
        BigDecimal quantity,
        String unit,
        BigDecimal price
) {
}
