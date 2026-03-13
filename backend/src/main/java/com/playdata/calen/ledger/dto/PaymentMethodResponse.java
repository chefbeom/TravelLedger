package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.PaymentMethodKind;

public record PaymentMethodResponse(
        Long id,
        String name,
        PaymentMethodKind kind,
        Integer displayOrder,
        boolean active
) {
}
