package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerAdjustmentGroupResponse(LedgerEntryResponse origin, List<LedgerEntryResponse> adjustments) {
}
