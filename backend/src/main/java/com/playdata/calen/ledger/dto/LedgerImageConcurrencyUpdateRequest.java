package com.playdata.calen.ledger.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LedgerImageConcurrencyUpdateRequest(
        @NotNull @DecimalMin("1") @DecimalMax("20") @Digits(integer = 2, fraction = 0) BigDecimal maxConcurrent) {
}
