package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import java.time.LocalDateTime;

public record LedgerClassificationRuleResponse(
        Long id,
        String keyword,
        String normalizedKeyword,
        EntryType entryType,
        Long categoryGroupId,
        String categoryGroupName,
        Long categoryDetailId,
        String categoryDetailName,
        Long paymentMethodId,
        String paymentMethodName,
        Integer priority,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}