package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.PaymentMethodKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentMethodRequest(
        @NotBlank(message = "결제수단명은 필수입니다.")
        String name,
        @NotNull(message = "결제수단 타입은 필수입니다.")
        PaymentMethodKind kind,
        @Min(value = 0, message = "표시 순서는 0 이상이어야 합니다.")
        Integer displayOrder
) {
}
