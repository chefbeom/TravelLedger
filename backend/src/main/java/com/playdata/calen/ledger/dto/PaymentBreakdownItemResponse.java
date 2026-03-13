package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;

public record PaymentBreakdownItemResponse(
        String paymentMethodName,
        PaymentMethodKind kind,
        BigDecimal totalAmount,
        long entryCount
) {
}
