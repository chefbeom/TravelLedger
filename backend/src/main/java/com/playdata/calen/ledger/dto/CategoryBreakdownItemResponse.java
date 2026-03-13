package com.playdata.calen.ledger.dto;

import java.math.BigDecimal;

public record CategoryBreakdownItemResponse(
        String groupName,
        String detailName,
        BigDecimal totalAmount,
        long entryCount
) {
}
