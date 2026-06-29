package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LedgerClassificationRuleRequest(
        @NotBlank @Size(max = 160) String keyword,
        EntryType entryType,
        @NotNull Long categoryGroupId,
        Long categoryDetailId,
        Long paymentMethodId,
        Integer priority,
        Boolean active
) {
}