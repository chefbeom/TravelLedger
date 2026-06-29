package com.playdata.calen.ledger.dto;

public record LedgerClassificationPreviewResponse(
        boolean matched,
        String reason,
        LedgerClassificationRuleResponse rule
) {
}