package com.playdata.calen.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LedgerSplitPaymentUpdateRequest(@NotBlank String revision, @NotNull @Valid LedgerSplitPaymentRequest payment) { }
