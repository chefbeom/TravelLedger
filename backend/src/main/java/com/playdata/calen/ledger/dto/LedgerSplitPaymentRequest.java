package com.playdata.calen.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record LedgerSplitPaymentRequest(
        @NotNull UUID requestId,
        @NotNull @Valid LedgerEntryRequest entry,
        @NotNull @Size(min = 2, max = 10) List<@NotNull @Valid Part> parts
) {
    public record Part(@NotNull Long paymentMethodId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            Integer installmentMonths) { }
}
